package io.github.alexarchambault.jsonrpc

import java.io.{File, FileNotFoundException}
import java.net.Socket

import com.typesafe.scalalogging.Logger
import org.scalasbt.ipcsocket.{UnixDomainSocket => JnaUnixDomainSocket}
import io.github.alexarchambault.jsonrpc.ipcsocket.svm.ErrnoException
import io.github.alexarchambault.jsonrpc.ipcsocket.svm.{UnixDomainSocket => SvmUnixDomainSocket}

import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.Try

final class Client(
  socketFile: File,
  onMessage: JsonRpcConnection => JsonRpcMessage => Either[JsonRpcError, Unit]
) {

  import Client.{isNativeImage, log}

  // FIXME Locks when writing these
  private var socketOpt = Option.empty[Socket]
  private var jsonRpcOpt = Option.empty[JsonRpc]

  def start(): Unit = {
    assert(socketOpt.isEmpty, "Already started")

    log.debug(s"Creating UnixDomainSocket(${socketFile.getAbsolutePath})")
    val socket =
      if (isNativeImage) {
        @tailrec
        def connect(s: SvmUnixDomainSocket, n: Int): Unit = {
          val connected =
            try {
              s.connect(socketFile.getAbsolutePath)
              true
            } catch {
              case _: FileNotFoundException if n <= 1000 =>
                false
              case NonFatal(t) =>
                throw t
            }

          if (!connected) {
            log.whenInfoEnabled {
              if (n % 100 == 0)
                log.info("Worker not listening yet")
            }
            Thread.sleep(10L)
            connect(s, n + 1)
          }
        }

        val s = new SvmUnixDomainSocket
        try connect(s, 0)
        catch {
          case NonFatal(t) =>
            s.close()
            throw t
        }
        s
      } else
        new JnaUnixDomainSocket(socketFile.getAbsolutePath)
    log.debug("Created UnixDomainSocket")
    var jsonRpc: JsonRpc = null
    lazy val onMessage0 = onMessage(jsonRpc)
    log.debug("Creating JsonRpc")
    jsonRpc = new JsonRpc(
      socket,
      msg => onMessage0(msg)
    )
    log.debug("Created JsonRpc")
    socketOpt = Some(socket)
    jsonRpcOpt = Some(jsonRpc)
  }

  def stop(): Unit = {
    jsonRpcOpt.foreach(_.shutdown())
    socketOpt.foreach(_.close())
    jsonRpcOpt = None
    socketOpt = None
  }

  def connection: JsonRpcConnection =
    jsonRpcOpt.getOrElse {
      sys.error("JSON-RPC session not started yet")
    }
}

object Client {

  private val log = Logger(classOf[Client])

  private lazy val isNativeImage = {
    val b = sys.props
      .get("org.graalvm.nativeimage.imagecode")
      .contains("runtime")
    log.debug(s"isNativeImage=$b")
    b
  }

  private[jsonrpc] def methodNotFoundError(m: JsonRpcMessage): Option[JsonRpcError] = {
    val methodOpt = m match {
      case r: JsonRpcMessage.Request => Some(r.method)
      case n: JsonRpcMessage.Notification => Some(n.method)
      case _ => None
    }
    methodOpt.map{ method =>
      new JsonRpcError(ErrorCodes.MethodNotFound, method)
    }
  }

  def apply(socketFile: File)(f: JsonRpcConnection => JsonRpcMessage => Option[Either[JsonRpcError, Unit]]): Client =
    new Client(
      socketFile,
      c => { val f0 = f(c); m => f0(m).getOrElse(methodNotFoundError(m).toLeft(())) }
    )
}
