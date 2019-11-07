package io.github.alexarchambault.jsonrpc

import java.nio.charset.StandardCharsets
import java.{util => ju}

import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.github.plokhotnyuk.jsoniter_scala.core._
import scala.util.hashing.MurmurHash3
import scala.util.Try

sealed abstract class JsonRpcMessage extends Product with Serializable {
  def jsonrpc: String
  def raw: Array[Byte]
  def idOpt: Option[String]
  final def serialize: Array[Byte] =
    JsonRpcMessage.serializeMessage(raw)
}

object JsonRpcMessage {

  def jsonRpcVersion = "2.0"

  // from …
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
    def raw: Array[Byte] =
      writeToArray(this)
    def idOpt: Option[String] =
      Some(id)
  }

  object Request {
    implicit val codec: JsonValueCodec[Request] =
      JsonCodecMaker.make[Request](CodecMakerConfig)

    def apply(id: String, method: String, params: Option[RawJson]): Request =
      Request(jsonRpcVersion, id, method, params)
  }


  final case class Notification(
    jsonrpc: String,
    method: String,
    params: Option[RawJson]
  ) extends JsonRpcMessage {
    def raw: Array[Byte] =
      writeToArray(this)
    def idOpt: Option[String] =
      None
  }

  object Notification {
    implicit val codec: JsonValueCodec[Notification] =
      JsonCodecMaker.make[Notification](CodecMakerConfig)

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
    def raw: Array[Byte] =
      writeToArray(this)
    def idOpt: Option[String] =
      id
  }

  object Response {
    implicit val codec: JsonValueCodec[Response] =
      JsonCodecMaker.make[Response](CodecMakerConfig)

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

    object Error {
      implicit val codec: JsonValueCodec[Error] =
        JsonCodecMaker.make[Error](CodecMakerConfig)
    }
  }

  private def serializeMessage(json: Array[Byte]): Array[Byte] = {
    val bodyLen = json.length

    Iterator(
      s"Content-Length: $bodyLen",
      "Content-Type: application/vscode-jsonrpc; charset=utf-8",
      ""
    ).mkString("", "\r\n", "\r\n").getBytes(StandardCharsets.UTF_8) ++ json
  }


}
