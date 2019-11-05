package io.github.alexarchambault.jsonrpc

import java.nio.charset.StandardCharsets

import com.github.plokhotnyuk.jsoniter_scala.core._
import io.github.alexarchambault.jsonrpc.JsonRpcMessage.RawJson

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

final case class JavaCall[A, B](
  methodName: String,
  inputCodec: JsonValueCodec[A],
  outputCodec: JsonValueCodec[B],
  local: (JsonRpcConnection, A) => Future[B]
) extends RemoteJavaCall[A, B] {

  def local(connection: JsonRpcConnection)(implicit ec: ExecutionContext, ev: Unit =:= A): Future[B] =
    local(connection, ev(()))

  def localFromMessage(connection: JsonRpcConnection, req: JsonRpcMessage.Request)(implicit ec: ExecutionContext): Either[JsonRpcError, Future[JsonRpcMessage.Response]] = {
    val bytes = req.params.map(_.bs).getOrElse("{}".getBytes(StandardCharsets.UTF_8))
    val futureInput = Future.fromTry(Try(readFromArray(bytes)(inputCodec)))
    val f = for {
      a <- futureInput
      b <- local(connection, a)
    } yield JsonRpcMessage.Response(Some(req.id), Some(RawJson(writeToArray(b)(outputCodec))))
    Right(f)
  }
}

object JavaCall {

  def apply[A: JsonValueCodec, B: JsonValueCodec](methodName: String)(f: (JsonRpcConnection, A) => Future[B]): JavaCall[A, B] =
    JavaCall(
      methodName,
      implicitly[JsonValueCodec[A]],
      implicitly[JsonValueCodec[B]],
      f
    )


  def onMessage(calls: Seq[JavaCall[_, _]])(implicit ec: ExecutionContext): (JsonRpcConnection, JsonRpcMessage) => Option[Either[JsonRpcError, Unit]] = {

    val map = calls.map(c => c.methodName -> c).toMap[String, JavaCall[_, _]]

    {
      case (conn, req: JsonRpcMessage.Request) =>
        map.get(req.method).map { c =>
          c.localFromMessage(conn, req).right.map { futureResp =>
            futureResp.onComplete { t =>
              val resp = t match {
                case Success(resp) => resp
                case Failure(t) =>
                  JsonRpcMessage.Response.error(
                    Some(req.id),
                    ErrorCodes.InternalError,
                    "Exception caught",
                    Some(RawJson(writeToArray(RemoteException.Repr(t))))
                  )
              }
              conn.respond(resp)
            }
          }
        }
      case _ =>
        None
    }
  }
}
