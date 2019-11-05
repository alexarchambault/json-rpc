package io.github.alexarchambault.jsonrpc

import java.nio.charset.StandardCharsets

import com.github.plokhotnyuk.jsoniter_scala.core._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait RemoteJavaCall[A, B] {
  def methodName: String
  def inputCodec: JsonValueCodec[A]
  def outputCodec: JsonValueCodec[B]

  def remote(connection: JsonRpcConnection, input: A)(implicit ec: ExecutionContext): Future[B] = {
    val f = connection.call(methodName, input)(inputCodec, ec)
    f.flatMap { resp =>
      resp.error match {
        case Some(error) =>
          val remoteExOpt =
            if (error.code == ErrorCodes.InternalError && error.message == "Exception caught")
              error
                .data
                .flatMap(r => Try(readFromArray[RemoteException.Repr](r.bs)).toOption)
                .map(_.instance())
            else
              None

          val ex = remoteExOpt.getOrElse {
            new Exception(
              s"Error calling $methodName: ${error.message} ${error.code}" +
                error.data.map(b => Try(new String(b.bs, StandardCharsets.UTF_8)).toOption.map(s => s" ($s)"))
            )
          }

          Future.failed(ex)
        case None =>
          val bytes = resp.result.map(_.bs).getOrElse("{}".getBytes(StandardCharsets.UTF_8))
          Future.fromTry(Try(readFromArray[B](bytes)(outputCodec)))
      }
    }
  }

  def remote(connection: JsonRpcConnection)(implicit ec: ExecutionContext, ev: Unit =:= A): Future[B] =
    remote(connection, ev(()))

  def withImplem(method: (JsonRpcConnection, A) => Future[B]): JavaCall[A, B] =
    JavaCall(methodName, inputCodec, outputCodec, method)
}

object RemoteJavaCall {

  private final case class PureRemoteCall[A, B](
    methodName: String,
    inputCodec: JsonValueCodec[A],
    outputCodec: JsonValueCodec[B]
  ) extends RemoteJavaCall[A, B]

  def apply[A, B](methodName: String)(implicit
    inputCodec: JsonValueCodec[A],
    outputCodec: JsonValueCodec[B]
  ): RemoteJavaCall[A, B] =
    PureRemoteCall(methodName, inputCodec, outputCodec)

}
