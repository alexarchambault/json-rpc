package io.github.alexarchambault.jsonrpc

import java.io.File
import java.net.{Socket, SocketTimeoutException}
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch

import com.typesafe.scalalogging.Logger
import org.scalasbt.ipcsocket.{UnixDomainSocketLibrary, UnixDomainServerSocket, UnixDomainSocket}

import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

final class Server(
  socketFile: File,
  listeningFile: File,
  onMessage: (JsonRpcConnection, JsonRpcMessage) => Either[JsonRpcError, Unit]
) {

  import Server.log

  private val running = new AtomicBoolean
  private val countDown = new CountDownLatch(1)
  // TODO Guard these with locks?
  private var serverSocketOpt = Option.empty[UnixDomainServerSocket]
  private var serverThreadOpt = Option.empty[Thread]

  private var jsonRpcs = Seq.empty[JsonRpc]
  private val jsonRpcsLock = new Object

  def start(): Unit = {

    if (Files.exists(listeningFile.toPath))
      sys.error(s"$listeningFile already exists")

    val maxSocketLength = new UnixDomainSocketLibrary.SockaddrUn().sunPath.length - 1
    val path = socketFile.getAbsolutePath
    if (path.lengthCompare(maxSocketLength) > 0)
      sys.error(s"Socket file path $socketFile too long")

    val alreadyRunning =
      try {
        new UnixDomainSocket(path)
        true
      } catch {
        case NonFatal(e) =>
          false
      }
    if (alreadyRunning)
      sys.error(s"Worker already running on $socketFile")

    Files.deleteIfExists(socketFile.toPath)
    Files.createDirectories(socketFile.toPath.getParent)
    Files.createDirectories(listeningFile.toPath.getParent)

    val serverSocket = new UnixDomainServerSocket(path)
    serverSocket.setSoTimeout(5000)

    val thread = new Thread("jsonrpc-worker") {
      setDaemon(true)
      override def run(): Unit = {
        try {
          val t = new Thread("write-listening-file") {
            setDaemon(true)
            override def run() = {
              log.debug(s"Waiting to write $listeningFile")
              Thread.sleep(100L)
              Files.write(listeningFile.toPath, Array.emptyByteArray)
              log.debug(s"Wrote $listeningFile")
            }
          }
          t.start()
          while (running.get())
            try {
              log.debug(s"Server thread waiting for connections on $path")
              val socket = serverSocket.accept()
              incomingConnection(socket)
            } catch {
              case _: SocketTimeoutException =>
            }
        } finally {
          Files.deleteIfExists(listeningFile.toPath)
        }

        serverSocket.close()
      }
    }

    serverSocketOpt = Some(serverSocket)
    serverThreadOpt = Some(thread)
    running.set(true)

    thread.start()
  }

  def stop(wait: Boolean = false): Unit = {
    running.set(false)
    jsonRpcsLock.synchronized {
      jsonRpcs.foreach(_.shutdown())
      jsonRpcs = Seq.empty
    }
    if (wait) {
      serverThreadOpt.foreach(_.join())
      try serverSocketOpt.foreach(_.close())
      catch {
        case NonFatal(_) =>
      }
    }
    serverThreadOpt = None
    serverSocketOpt = None

    countDown.countDown()
  }

  private def incomingConnection(socket: Socket): Unit = {
    var jsonRpc: JsonRpc = null
    jsonRpc = new JsonRpc(socket, msg => onMessage(jsonRpc, msg))
    jsonRpcsLock.synchronized {
      jsonRpcs = jsonRpcs :+ jsonRpc
    }
  }

  def join(timeout: Duration): Unit =
    countDown.await(timeout.length, timeout.unit)
  def join(): Unit =
    countDown.await()

}

object Server {

  private val log = Logger(classOf[Server])

  def socketFileProperty = "jsonrpc.socket"
  def listeningFileProperty = "jsonrpc.listening"

  def defaultSocketFile(): File =
    sys.props
      .get(socketFileProperty)
      .map(new File(_))
      .getOrElse {
        sys.error(s"$socketFileProperty not set")
      }

  def defaultListeningFile(): File =
    sys.props
      .get(listeningFileProperty)
      .map(new File(_))
      .getOrElse {
        sys.error(s"$listeningFileProperty not set")
      }

  def apply(
    socketFile: File,
    listeningFile: File,
    onMessage: (JsonRpcConnection, JsonRpcMessage) => Option[Either[JsonRpcError, Unit]]
  ): Server =
    new Server(
      socketFile,
      listeningFile,
      (c, m) => onMessage(c, m).getOrElse(Client.methodNotFoundError(m).toLeft(()))
    )

  def apply(
    socketFile: File,
    listeningFile: File,
    javaCalls: Seq[JavaCall[_, _]]
  )(implicit ec: ExecutionContext): Server =
    new Server(
      socketFile,
      listeningFile,
      {
        val f = JavaCall.onMessage(javaCalls)
        (c, m) => f(c, m).getOrElse(Client.methodNotFoundError(m).toLeft(()))
      }
    )

  def apply(
    onMessage: (JsonRpcConnection, JsonRpcMessage) => Option[Either[JsonRpcError, Unit]]
  ): Server =
    new Server(
      defaultSocketFile(),
      defaultListeningFile(),
      (c, m) => onMessage(c, m).getOrElse(Client.methodNotFoundError(m).toLeft(()))
    )

  def apply(
    javaCalls: Seq[JavaCall[_, _]]
  )(implicit ec: ExecutionContext): Server =
    new Server(
      defaultSocketFile(),
      defaultListeningFile(),
      {
        val f = JavaCall.onMessage(javaCalls)
        (c, m) => f(c, m).getOrElse(Client.methodNotFoundError(m).toLeft(()))
      }
    )
}
