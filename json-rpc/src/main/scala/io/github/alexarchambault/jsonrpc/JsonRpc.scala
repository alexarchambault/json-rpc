package io.github.alexarchambault.jsonrpc

import java.net.Socket
import java.{util => ju}
import java.util.concurrent.ConcurrentHashMap

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

final class JsonRpc(
  connection: Socket,
  onMessage: JsonRpcMessage => Either[JsonRpcError, Unit]
) extends JsonRpcConnection {

  connection.setSoTimeout(5000)

  import JsonRpc._

  private val onGoingCalls = new ConcurrentHashMap[String, Promise[JsonRpcMessage.Response]]
  private val onResponse: JsonRpcMessage => Option[Either[JsonRpcError, Unit]] = {
    case resp: JsonRpcMessage.Response =>
      resp.id.flatMap(id => Option(onGoingCalls.get(id))).map { p =>
        p.complete(Success(resp))
        onGoingCalls.remove(resp.id)
        Right(())
      }
    case _ =>
      None
  }

  private val onMessage0: JsonRpcMessage => Either[JsonRpcError, Unit] = { m =>
    onResponse(m).getOrElse(onMessage(m))
  }
  private val writerThread = new WriterThread(connection.getOutputStream)
  writerThread.start()
  private val readerThread = new ReaderThread(
    connection.getInputStream,
    onMessage0,
    b => writerThread.write(b) // discarding a Future here, but WriterThread should log in case of failure
  )
  readerThread.start()

  def call[A: JsonValueCodec](method: String, params: A)(implicit ec: ExecutionContext): Future[JsonRpcMessage.Response] = {
    val id = ju.UUID.randomUUID().toString
    val params0 = JsonRpcMessage.RawJson(writeToArray(params))
    val m = JsonRpcMessage.Request(id, method, Some(params0))
    log.debug(s"Sending request $m")
    val p = Promise[JsonRpcMessage.Response]
    onGoingCalls.put(id, p)
    writerThread.write(m.serialize).onComplete {
      case Failure(t) =>
        onGoingCalls.remove(id)
        p.complete(Failure(t))
      case Success(()) =>
        // just let the response complete p
    }
    p.future
  }

  def notify[A: JsonValueCodec](method: String, params: A): Future[Unit] = {
    val json = writeToArray(params)
    val m = JsonRpcMessage.Notification(method, Some(JsonRpcMessage.RawJson(json)))
    log.debug(s"Sending notification $m")
    writerThread.write(m.serialize)
  }

  def respond(resp: JsonRpcMessage.Response): Future[Unit] = {
    log.debug(s"Sending response $resp")
    writerThread.write(resp.serialize)
  }

  def shutdown(): Unit = {
    readerThread.shutdown()
    writerThread.shutdown()
  }
}

object JsonRpc {
  private val log = Logger(classOf[JsonRpc])
}
