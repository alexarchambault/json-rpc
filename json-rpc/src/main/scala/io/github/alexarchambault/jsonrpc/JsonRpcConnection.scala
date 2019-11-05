package io.github.alexarchambault.jsonrpc

import com.github.plokhotnyuk.jsoniter_scala.core._

import scala.concurrent.{ExecutionContext, Future}

trait JsonRpcConnection {
  def call[A: JsonValueCodec](method: String, params: A)(implicit ec: ExecutionContext): Future[JsonRpcMessage.Response]
  def notify[A: JsonValueCodec](method: String, params: A): Future[Unit]
  def respond(resp: JsonRpcMessage.Response): Future[Unit]
  final def respond[A: JsonValueCodec](event: A, execId: Option[String]): Future[Unit] = {
    val json = writeToArray(event)
    val m = JsonRpcMessage.Response(execId, Some(JsonRpcMessage.RawJson(json)))
    respond(m)
  }
}