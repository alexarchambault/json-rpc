package io.github.alexarchambault.jsonrpc

import java.io.File
import java.net.{URL, URLClassLoader}
import java.nio.file.Files

import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext
import scala.sys.ShutdownHookThread


final class Master(
  val initialClassPath: Seq[File],
  val mainClass: String,
  val args: Seq[String],
  val onMessage: JsonRpcConnection => JsonRpcMessage => Option[Either[JsonRpcError, Unit]] = _ => _ => None
) {
  import Master.log

  private var processOpt = Option.empty[Process]
  private var clientOpt = Option.empty[Client]
  private var shutdownHookThreadOpt = Option.empty[ShutdownHookThread]

  def start(): Unit = {

    val tmpDir = Files.createTempDirectory("jsonrpc")
    shutdownHookThreadOpt = Some(
      sys.addShutdownHook {
        Master.deleteRecursively(tmpDir.toFile)
        stop0()
      }
    )

    val socketFile = tmpDir
      .resolve("socket")
      .toFile
      .getAbsoluteFile

    val listeningFile = tmpDir
      .resolve("listening")
      .toFile
      .getAbsoluteFile

    val cmd = Seq("java") ++
      sys.props.get("jna.debug_load.jna").map(v => s"-Djna.debug_load.jna=$v").toSeq ++
      sys.props.get("jna.debug_load").map(v => s"-Djna.debug_load=$v").toSeq ++
      Seq(
        s"-D${Server.socketFileProperty}=${socketFile.getAbsolutePath}",
        s"-D${Server.listeningFileProperty}=${listeningFile.getAbsolutePath}",
        "-cp",
        initialClassPath.map(_.getAbsolutePath()).mkString(File.pathSeparator),
        mainClass
      ) ++
      args

    log.debug(s"Running command\n" + cmd.map("  " + _ + "\n").mkString)

    val b = new ProcessBuilder(cmd: _*)
    b.inheritIO()
    val p = b.start()
    processOpt = Some(p)

    val client = Client(socketFile)(onMessage)

    clientOpt = Some(client)

    log.debug(s"Waiting for $listeningFile to be created")
    while (!listeningFile.exists() && p.isAlive()) {
      Thread.sleep(20L)
    }
    if (p.isAlive()) {
      log.debug(s"Found $listeningFile")
      client.start()
    } else
      throw new Exception(s"Error starting worker (return code: ${p.exitValue()})")
  }

  private def stop0(): Unit = {
    processOpt.foreach(_.destroy())
    processOpt = None
    clientOpt.foreach(_.stop())
    clientOpt = None
  }

  def stop(): Unit = {
    stop0()
    shutdownHookThreadOpt.foreach(_.remove())
    shutdownHookThreadOpt = None
  }

  def connection: JsonRpcConnection =
    clientOpt.map(_.connection).getOrElse {
      ???
    }

}

object Master {

  private def deleteRecursively(f: File): Unit = {
    if (f.isDirectory)
      Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    Files.deleteIfExists(f.toPath)
  }

  def apply(
    initialClassPath: Seq[File],
    mainClass: String,
    args: Seq[String],
    onMessage: JsonRpcConnection => JsonRpcMessage => Option[Either[JsonRpcError, Unit]]
  ): Master =
    new Master(
      initialClassPath,
      mainClass,
      args,
      onMessage
    )

  private lazy val isNativeImage: Boolean =
    sys.props
      .get("org.graalvm.nativeimage.imagecode")
      .contains("runtime")


  def currentClassPath(): Seq[File] =
    if (isNativeImage)
      sys.props
        .get("java.class.path")
        .toSeq
        .flatMap(_.split(File.pathSeparator).toSeq)
        .map(new File(_))
    else {

      def helper(cl: ClassLoader): Stream[URL] =
        if (cl == null) Stream()
        else
          cl match {
            case u: URLClassLoader =>
              u.getURLs.toStream #::: helper(cl.getParent)
            case _ =>
              helper(cl.getParent)
          }

      helper(Thread.currentThread().getContextClassLoader)
        .iterator
        .flatMap { u =>
          if (u.getProtocol == "file")
            Iterator(new File(u.toURI))
          else
            Iterator.empty
        }
        .toVector
    }

  def apply(
    mainClass: String,
    args: Seq[String],
    calls: Seq[Call[_, _]]
  )(implicit ec: ExecutionContext): Master =
    Master(
      currentClassPath(),
      mainClass,
      args,
      calls
    )

  def apply(
    initialClassPath: Seq[File],
    mainClass: String,
    args: Seq[String],
    calls: Seq[Call[_, _]]
  )(implicit ec: ExecutionContext): Master =
    new Master(
      initialClassPath,
      mainClass,
      args,
      onMessage = {
        val f = Call.onMessage(calls)
        conn => msg => f(conn, msg)
      }
    )

    private lazy val log = Logger(classOf[Master])
}
