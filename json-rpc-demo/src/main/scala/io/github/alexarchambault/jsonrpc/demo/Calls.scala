package io.github.alexarchambault.jsonrpc.demo

import java.lang.management.ManagementFactory

import com.oracle.svm.core.posix.headers.Unistd
import com.typesafe.scalalogging.Logger
import io.github.alexarchambault.jsonrpc.{Call, RemoteCall}

import scala.concurrent.{ExecutionContext, Future}

object Calls {

  final case class PidResponse(pid: Int)

  final case class Large(length: Int)

  final case class LargeResponse(dummy: String) {
    override def toString = s"LargeResponse([${dummy.length} characters])"
  }


  private object Codecs {

    import com.github.plokhotnyuk.jsoniter_scala.core._
    import com.github.plokhotnyuk.jsoniter_scala.macros._

    implicit val unitCodec: JsonValueCodec[Unit] = {
      final case class Empty()
      val empty = Empty()
      val emptyCodec = JsonCodecMaker.make[Empty](CodecMakerConfig)

      new JsonValueCodec[Unit] {
        def decodeValue(in: JsonReader, default: Unit) = emptyCodec.decodeValue(in, empty)
        def encodeValue(x: Unit, out: JsonWriter) = emptyCodec.encodeValue(empty, out)
        def nullValue = ()
      }
    }

    implicit val pidResponseCodec: JsonValueCodec[PidResponse] =
      JsonCodecMaker.make[PidResponse](CodecMakerConfig)

    implicit val largeCodec: JsonValueCodec[Large] =
      JsonCodecMaker.make[Large](CodecMakerConfig)

    implicit val largeResponseCodec: JsonValueCodec[LargeResponse] =
      JsonCodecMaker.make[LargeResponse](CodecMakerConfig)

  }

  import Codecs._


  val pid = RemoteCall[Unit, PidResponse]("pid")
  val large = RemoteCall[Large, LargeResponse]("large")
  val fail = RemoteCall[Unit, Unit]("fail")


  private lazy val log = Logger(getClass)

  def calls(implicit ec: ExecutionContext) = Seq[Call[_, _]](
    pid.withImplem {

      lazy val isNativeImage: Boolean =
        sys.props
          .get("org.graalvm.nativeimage.imagecode")
          .contains("runtime")

      def getPid(): Int =
        ManagementFactory.getRuntimeMXBean
          .getName
          .takeWhile(_ != '@')
          .toInt

      (_, _) =>
        Future {
          val pid0 = if (isNativeImage) Unistd.getpid() else getPid()
          log.debug(s"PID: $pid0")
          PidResponse(pid0)
        }
    },
    large.withImplem {
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
    },
    fail.withImplem {
      (_, _) =>
        Future.failed(new Exception("foo"))
    }
  )

}
