package io.github.alexarchambault.jsonrpc.demo

import java.lang.management.ManagementFactory

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.oracle.svm.core.posix.headers.Unistd
import com.typesafe.scalalogging.Logger
import io.github.alexarchambault.jsonrpc.{JavaCall, Server}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object Calls {

  private lazy val log = Logger(getClass)

  final case class Empty()

  implicit val emptyCodec: JsonValueCodec[Empty] =
    JsonCodecMaker.make(CodecMakerConfig)

  final case class PidResponse(pid: Int)

  implicit val pidResponseCodec: JsonValueCodec[PidResponse] =
    JsonCodecMaker.make(CodecMakerConfig)

  private lazy val isNativeImage: Boolean =
    sys.props
      .get("org.graalvm.nativeimage.imagecode")
      .contains("runtime")

  private def getPid(): Int =
    ManagementFactory.getRuntimeMXBean
      .getName
      .takeWhile(_ != '@')
      .toInt

  def pid(implicit ec: ExecutionContext) = JavaCall[Empty, PidResponse]("pid") {
    (_, _) =>
      Future {
        val pid0 = if (isNativeImage) Unistd.getpid() else getPid()
        log.debug(s"PID: $pid0")
        PidResponse(pid0)
      }
  }

  val fail = JavaCall[Empty, Empty]("fail") {
    (_, _) =>
      Future.failed(new Exception("foo"))
  }


  def calls(implicit ec: ExecutionContext) = Seq[JavaCall[_, _]](
    pid,
    fail
  )

  object Special {

    val clientStop = JavaCall[Empty, Empty]("stop") {
      (_, _) =>
        ???
    }

    def serverStop(server: => Server) = clientStop.withImplem {
      (_, _) =>
        Future.fromTry(
          Try {
            server.stop()
            Empty()
          }
        )
    }

  }

}
