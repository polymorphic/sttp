package sttp.client4.curl

import sttp.capabilities.Effect
import sttp.client4.curl.internal.CurlApi._
import sttp.client4.curl.internal.CurlCode.CurlCode
import sttp.client4.curl.internal.CurlInfo._
import sttp.client4.curl.internal.CurlOption.{Header => _, _}
import sttp.client4.curl.internal._
import sttp.client4.internal._
import sttp.client4._
import sttp.client4.ws.{GotAWebSocketException, NotAWebSocketException}
import sttp.model._
import sttp.monad.MonadError
import sttp.monad.syntax._

import scala.collection.immutable.Seq
import scala.io.Source
import scala.scalanative.libc.stdio.{fclose, fopen, FILE}
import scala.scalanative.libc.stdlib._
import scala.scalanative.libc.string._
import scala.scalanative.unsafe
import scala.scalanative.unsafe.{CSize, Ptr, _}
import scala.scalanative.unsigned._

abstract class AbstractCurlBackend[F[_]](_monad: MonadError[F], verbose: Boolean) extends GenericBackend[F, Any] {
  override implicit def monad: MonadError[F] = _monad

  type R = Any with Effect[F]

  override def close(): F[Unit] = monad.unit(())

  private var headers: CurlList = _
  private var multiPartHeaders: Seq[CurlList] = Seq()

  override def send[T](request: GenericRequest[T, R]): F[Response[T]] =
    adjustExceptions(request) {
      unsafe.Zone { implicit z =>
        val curl = CurlApi.init
        if (verbose) {
          curl.option(Verbose, parameter = true)
        }
        if (request.tags.nonEmpty) {
          monad.error(new UnsupportedOperationException("Tags are not supported"))
        }
        val reqHeaders = request.headers
        if (reqHeaders.nonEmpty) {
          reqHeaders.find(_.name == "Accept-Encoding").foreach(h => curl.option(AcceptEncoding, h.value))
          request.body match {
            case _: MultipartBody[_] =>
              headers = transformHeaders(
                reqHeaders :+ Header.contentType(MediaType.MultipartFormData)
              )
            case _ =>
              headers = transformHeaders(reqHeaders)
          }
          curl.option(HttpHeader, headers.ptr)
        }

        val spaces = responseSpace
        FileHelpers.getFilePath(request.response.delegate) match {
          case Some(file) => handleFile(request, curl, file, spaces)
          case None       => handleBase(request, curl, spaces)
        }
      }
    }

  private def adjustExceptions[T](request: GenericRequest[_, _])(t: => F[T]): F[T] =
    SttpClientException.adjustExceptions(monad)(t)(
      SttpClientException.defaultExceptionToSttpClientException(request, _)
    )

  private def handleBase[T](request: GenericRequest[T, R], curl: CurlHandle, spaces: CurlSpaces)(implicit
      z: unsafe.Zone
  ) = {
    curl.option(WriteFunction, AbstractCurlBackend.wdFunc)
    curl.option(WriteData, spaces.bodyResp)
    curl.option(TimeoutMs, request.options.readTimeout.toMillis)
    curl.option(HeaderData, spaces.headersResp)
    curl.option(Url, request.uri.toString)
    setMethod(curl, request.method)
    setRequestBody(curl, request.body)
    monad.flatMap(lift(curl.perform)) { _ =>
      curl.info(ResponseCode, spaces.httpCode)
      val responseBody = fromCString((!spaces.bodyResp)._1)
      val responseHeaders_ = parseHeaders(fromCString((!spaces.headersResp)._1))
      val httpCode = StatusCode((!spaces.httpCode).toInt)
      if (headers.ptr != null) headers.ptr.free()
      multiPartHeaders.foreach(_.ptr.free())
      free((!spaces.bodyResp)._1)
      free((!spaces.headersResp)._1)
      free(spaces.bodyResp.asInstanceOf[Ptr[CSignedChar]])
      free(spaces.headersResp.asInstanceOf[Ptr[CSignedChar]])
      free(spaces.httpCode.asInstanceOf[Ptr[CSignedChar]])
      curl.cleanup()

      val statusText = responseHeaders_.head.name.split(" ").last
      val responseHeaders = responseHeaders_.tail
      val responseMetadata = ResponseMetadata(httpCode, statusText, responseHeaders)

      val body: F[T] = bodyFromResponseAs(request.response, responseMetadata, Left(responseBody))
      monad.map(body) { b =>
        Response[T](
          body = b,
          code = httpCode,
          statusText = statusText,
          headers = responseHeaders,
          history = Nil,
          request = request.onlyMetadata
        )
      }
    }
  }

  private def handleFile[T](request: GenericRequest[T, R], curl: CurlHandle, file: SttpFile, spaces: CurlSpaces)(
      implicit z: unsafe.Zone
  ) = {
    val outputPath = file.toPath.toString
    val outputFilePtr: Ptr[FILE] = fopen(toCString(outputPath), toCString("wb"))
    curl.option(WriteData, outputFilePtr)
    curl.option(Url, request.uri.toString)
    setMethod(curl, request.method)
    setRequestBody(curl, request.body)
    monad.flatMap(lift(curl.perform)) { _ =>
      curl.info(ResponseCode, spaces.httpCode)
      val httpCode = StatusCode((!spaces.httpCode).toInt)
      if (headers.ptr != null) headers.ptr.free()
      multiPartHeaders.foreach(_.ptr.free())
      free(spaces.httpCode.asInstanceOf[Ptr[CSignedChar]])
      fclose(outputFilePtr)
      curl.cleanup()
      val responseMetadata = ResponseMetadata(httpCode, "", List.empty)
      val body: F[T] = bodyFromResponseAs(request.response, responseMetadata, Left(outputPath))
      monad.map(body) { b =>
        Response[T](
          body = b,
          code = httpCode,
          statusText = "",
          headers = List(Header.contentLength(file.size)),
          history = Nil,
          request = request.onlyMetadata
        )
      }
    }
  }

  private def setMethod(handle: CurlHandle, method: Method)(implicit z: Zone): F[CurlCode] = {
    val m = method match {
      case Method.GET     => handle.option(HttpGet, true)
      case Method.HEAD    => handle.option(Head, true)
      case Method.POST    => handle.option(Post, true)
      case Method.PUT     => handle.option(CustomRequest, "PUT")
      case Method.DELETE  => handle.option(CustomRequest, "DELETE")
      case Method.OPTIONS => handle.option(RtspRequest, true)
      case Method.PATCH   => handle.option(CustomRequest, "PATCH")
      case Method.CONNECT => handle.option(ConnectOnly, true)
      case Method.TRACE   => handle.option(CustomRequest, "TRACE")
      case Method(m)      => handle.option(CustomRequest, m)
    }
    lift(m)
  }

  private def setRequestBody(curl: CurlHandle, body: GenericRequestBody[R])(implicit zone: Zone): F[CurlCode] =
    body match { // todo: assign to monad object
      case b: BasicBodyPart =>
        val str = basicBodyToString(b)
        lift(curl.option(PostFields, toCString(str)))
      case m: MultipartBody[R] =>
        val mime = curl.mime
        m.parts.foreach { case p @ Part(name, partBody, _, headers) =>
          val part = mime.addPart()
          part.withName(name)
          val str = basicBodyToString(partBody)
          part.withData(str)
          p.fileName.foreach(part.withFileName(_))
          p.contentType.foreach(part.withMimeType(_))

          val otherHeaders = headers.filterNot(_.is(HeaderNames.ContentType))
          if (otherHeaders.nonEmpty) {
            val curlList = transformHeaders(otherHeaders)
            part.withHeaders(curlList.ptr)
            multiPartHeaders = multiPartHeaders :+ curlList
          }
        }
        lift(curl.option(Mimepost, mime))
      case StreamBody(_) =>
        monad.error(new IllegalStateException("CurlBackend does not support stream request body"))
      case NoBody =>
        monad.unit(CurlCode.Ok)
    }

  private def basicBodyToString(body: BodyPart[_]): String =
    body match {
      case StringBody(b, _, _)   => b
      case ByteArrayBody(b, _)   => new String(b)
      case ByteBufferBody(b, _)  => new String(b.array)
      case InputStreamBody(b, _) => Source.fromInputStream(b).mkString
      case FileBody(f, _)        => Source.fromFile(f.toFile).mkString
      case _                     => throw new IllegalArgumentException(s"Unsupported body: $body")
    }

  private def responseSpace: CurlSpaces = {
    val bodyResp = malloc(sizeof[CurlFetch]).asInstanceOf[Ptr[CurlFetch]]
    (!bodyResp)._1 = calloc(4096.toUInt, sizeof[CChar])
    (!bodyResp)._2 = 0.toUInt
    val headersResp = malloc(sizeof[CurlFetch]).asInstanceOf[Ptr[CurlFetch]]
    (!headersResp)._1 = calloc(4096.toUInt, sizeof[CChar])
    (!headersResp)._2 = 0.toUInt
    val httpCode = malloc(sizeof[Long]).asInstanceOf[Ptr[Long]]
    new CurlSpaces(bodyResp, headersResp, httpCode)
  }

  private def parseHeaders(str: String): Seq[Header] = {
    val array = str
      .split("\n")
      .filter(_.trim.length > 0)
    Seq(array: _*)
      .map { line =>
        val split = line.split(":", 2)
        if (split.size == 2)
          Header(split(0).trim, split(1).trim)
        else
          Header(split(0).trim, "")
      }
  }

  private lazy val bodyFromResponseAs = new BodyFromResponseAs[F, String, Nothing, Nothing] {
    override protected def withReplayableBody(
        response: String,
        replayableBody: Either[Array[Byte], SttpFile]
    ): F[String] = response.unit

    override protected def regularIgnore(response: String): F[Unit] = ().unit

    override protected def regularAsByteArray(response: String): F[Array[Byte]] = toByteArray(response)

    override protected def regularAsFile(response: String, file: SttpFile): F[SttpFile] =
      monad.unit(file)

    override protected def regularAsStream(response: String): F[(Nothing, () => F[Unit])] =
      throw new IllegalStateException("CurlBackend does not support streaming responses")

    override protected def handleWS[T](
        responseAs: GenericWebSocketResponseAs[T, _],
        meta: ResponseMetadata,
        ws: Nothing
    ): F[T] = ws

    override protected def cleanupWhenNotAWebSocket(
        response: String,
        e: NotAWebSocketException
    ): F[Unit] = ().unit

    override protected def cleanupWhenGotWebSocket(response: Nothing, e: GotAWebSocketException): F[Unit] = response
  }

  private def transformHeaders(reqHeaders: Iterable[Header])(implicit z: Zone): CurlList =
    reqHeaders
      .map(header => s"${header.name}: ${header.value}")
      .foldLeft(new CurlList(null)) { case (acc, h) =>
        new CurlList(acc.ptr.append(h))
      }

  private def toByteArray(str: String): F[Array[Byte]] = monad.unit(str.getBytes)

  private def lift(code: CurlCode): F[CurlCode] =
    code match {
      case CurlCode.Ok => monad.unit(code)
      case _           => monad.error(new RuntimeException(s"Command failed with status $code"))
    }
}

object AbstractCurlBackend {
  val wdFunc: CFuncPtr4[Ptr[Byte], CSize, CSize, Ptr[CurlFetch], CSize] = {
    (ptr: Ptr[CChar], size: CSize, nmemb: CSize, data: Ptr[CurlFetch]) =>
      val index: CSize = (!data)._2
      val increment: CSize = size * nmemb
      (!data)._2 = (!data)._2 + increment
      (!data)._1 = realloc((!data)._1, (!data)._2 + 1.toUInt)
      memcpy((!data)._1 + index, ptr, increment)
      !(!data)._1.+((!data)._2) = 0.toByte
      size * nmemb
  }
}
