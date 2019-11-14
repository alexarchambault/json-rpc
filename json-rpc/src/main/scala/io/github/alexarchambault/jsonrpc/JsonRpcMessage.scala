package io.github.alexarchambault.jsonrpc

import java.nio.charset.StandardCharsets
import java.{util => ju}

import scala.util.hashing.MurmurHash3
import scala.util.Try
import com.typesafe.scalalogging.Logger
import scala.util.Failure
import scala.util.Success

sealed abstract class JsonRpcMessage extends Product with Serializable {
  def jsonrpc: String
  def idOpt: Option[String]
}

object JsonRpcMessage {

  def jsonRpcVersion = "2.0"

  // adapted fromhttps://github.com/plokhotnyuk/jsoniter-scala/blob/209d918a030b188f064ee55505a6c47257731b4b/jsoniter-scala-macros/src/test/scala/com/github/plokhotnyuk/jsoniter_scala/macros/JsonCodecMakerSpec.scala#L645-L666
  final case class RawJson(bs: Array[Byte]) {
    override lazy val hashCode: Int = MurmurHash3.arrayHash(bs)
    override def equals(obj: Any): Boolean = obj match {
      case that: RawJson => ju.Arrays.equals(bs, that.bs)
      case _ => false
    }
    override def toString: String =
      Try(new String(bs, StandardCharsets.UTF_8))
        .toOption
        .getOrElse(bs.toString)
  }

  object RawJson {
    import com.github.plokhotnyuk.jsoniter_scala.core._
    implicit val codec: JsonValueCodec[RawJson] = new JsonValueCodec[RawJson] {
      def decodeValue(in: JsonReader, default: RawJson): RawJson =
        new RawJson(in.readRawValAsBytes())
      def encodeValue(x: RawJson, out: JsonWriter): Unit =
        out.writeRawVal(x.bs)
      val nullValue: RawJson =
        new RawJson(new Array[Byte](0))
    }
  }

  final case class Request(
    jsonrpc: String,
    id: String,
    method: String,
    params: Option[RawJson]
  ) extends JsonRpcMessage {
    def idOpt: Option[String] =
      Some(id)
  }

  object Request {
    def apply(id: String, method: String, params: Option[RawJson]): Request =
      Request(jsonRpcVersion, id, method, params)
  }


  final case class Notification(
    jsonrpc: String,
    method: String,
    params: Option[RawJson]
  ) extends JsonRpcMessage {
    def idOpt: Option[String] =
      None
  }

  object Notification {
    def apply(
      method: String,
      params: Option[RawJson]
    ): Notification =
      Notification(
        jsonRpcVersion,
        method,
        params
      )
  }

  final case class Response(
    jsonrpc: String,
    id: Option[String],
    result: Option[RawJson],
    error: Option[Response.Error]
  ) extends JsonRpcMessage {
    def idOpt: Option[String] =
      id
  }

  object Response {
    def apply(
      id: Option[String],
      result: Option[RawJson]
    ): Response =
      Response(
        jsonRpcVersion,
        id,
        result,
        None
      )

    def error(
      id: Option[String],
      code: Long,
      message: String
    ): Response =
      error(
        id,
        code,
        message,
        None
      )

    def error(
      id: Option[String],
      code: Long,
      message: String,
      data: Option[RawJson]
    ): Response =
      Response(
        jsonRpcVersion,
        id,
        None,
        Some(Error(code, message, data))
      )

    final case class Error(
      code: Long,
      message: String,
      data: Option[RawJson] = None
    )
  }

  private def serializeMessage(json: Array[Byte]): Array[Byte] = {
    val bodyLen = json.length

    Iterator(
      s"Content-Length: $bodyLen",
      "Content-Type: application/vscode-jsonrpc; charset=utf-8",
      ""
    ).mkString("", "\r\n", "\r\n").getBytes(StandardCharsets.UTF_8) ++ json
  }

  private final case class Probe(method: Option[String], id: Option[String])

  private object Codecs {
    import com.github.plokhotnyuk.jsoniter_scala.core._
    import com.github.plokhotnyuk.jsoniter_scala.macros._
    implicit val responseCodec: JsonValueCodec[Response] =
      JsonCodecMaker.make[Response](CodecMakerConfig)
    implicit val requestCodec: JsonValueCodec[Request] =
      JsonCodecMaker.make[Request](CodecMakerConfig)
    implicit val notificationCodec: JsonValueCodec[Notification] =
      JsonCodecMaker.make[Notification](CodecMakerConfig)
    implicit val errorCodec: JsonValueCodec[Response.Error] =
      JsonCodecMaker.make[Response.Error](CodecMakerConfig)
    implicit val probeCodec: JsonValueCodec[Probe] =
      JsonCodecMaker.make[Probe](CodecMakerConfig)
  }

  implicit final class Ops(private val msg: JsonRpcMessage) extends AnyVal {
    def serialize: Array[Byte] = {
      import com.github.plokhotnyuk.jsoniter_scala.core._
      import Codecs._
      val raw = msg match {
        case n: Notification => writeToArray(n)
        case r: Request => writeToArray(r)
        case r: Response => writeToArray(r)
      }
      JsonRpcMessage.serializeMessage(raw)
    }
  }

  private val log = Logger(classOf[JsonRpcMessage])

  def deserialize(buf: Array[Byte], offset: Int, len: Int): Either[String, JsonRpcMessage] = {
    import com.github.plokhotnyuk.jsoniter_scala.core._
    import Codecs._
    Try(readFromSubArray[Probe](buf, offset, offset + len)) match {
      case Failure(t) =>
        log.debug("Could not probe message", t)
        Left("Malformed message")
      case Success(check) =>
        val hasMethod = check.method.nonEmpty
        if (hasMethod) {
          val hasId = check.id.nonEmpty
          if (hasId)
            Try(readFromSubArray[Request](buf, offset, offset + len)) match {
              case Failure(t) =>
                log.debug("Could not decode request", t)
                Left("Malformed request")
              case Success(req) =>
                Right(req)
            }
          else
            Try(readFromSubArray[Notification](buf, offset, offset + len)) match {
              case Failure(t) =>
                log.debug("Could not decode notification", t)
                Left("Malformed notification")
              case Success(notif) =>
                Right(notif)
            }
        } else
          Try(readFromSubArray[Response](buf, offset, offset + len)) match {
            case Failure(t) =>
              log.debug("Could not decode response", t)
              Left("Malformed response")
            case Success(resp) =>
              Right(resp)
          }
    }
  }

}
