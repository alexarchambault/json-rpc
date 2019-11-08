package io.github.alexarchambault.jsonrpc.demo

import java.lang.management.ManagementFactory

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.oracle.svm.core.posix.headers.Unistd
import com.typesafe.scalalogging.Logger
import io.github.alexarchambault.jsonrpc.{Call, Server}

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

  final case class Large(length: Int)

  implicit val largeCodec: JsonValueCodec[Large] =
    JsonCodecMaker.make(CodecMakerConfig)

  final case class LargeResponse(dummy: String) {
    override def toString = s"LargeResponse([${dummy.length} characters])"
  }

  implicit val largeResponseCodec: JsonValueCodec[LargeResponse] =
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

  def pid(implicit ec: ExecutionContext) = Call[Empty, PidResponse]("pid") {
    (_, _) =>
      Future {
        val pid0 = if (isNativeImage) Unistd.getpid() else getPid()
        log.debug(s"PID: $pid0")
        PidResponse(pid0)
      }
  }

  def large(implicit ec: ExecutionContext) = Call[Large, LargeResponse]("large") {
    (_, input) =>
      Future {
        log.debug(s"Large: ${input.length}")
        var elem = "*"
        var i = 0
        val b = new StringBuilder
        var rem = input.length
        while (rem > 0) {
          if ((rem & 1) == 1) {
            b.append(elem)
          }
          rem = rem >> 1
          elem = elem + elem
          i += 1
        }
        val s = b.result()
        assert(s.length == input.length)
        LargeResponse(s)
      }
  }

  val fail = Call[Empty, Empty]("fail") {
    (_, _) =>
      Future.failed(new Exception("foo"))
  }


  def calls(implicit ec: ExecutionContext) = Seq[Call[_, _]](
    pid,
    large,
    fail
  )

  object Special {

    val clientStop = Call[Empty, Empty]("stop") {
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
