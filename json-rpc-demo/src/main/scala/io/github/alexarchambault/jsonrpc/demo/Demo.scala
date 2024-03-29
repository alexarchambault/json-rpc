package io.github.alexarchambault.jsonrpc.demo

import caseapp._
import com.typesafe.scalalogging.Logger
import io.github.alexarchambault.jsonrpc.{JsonRpcConnection, Master, RemoteCall}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration

object Demo extends CaseApp[Options] {
  private val log = Logger(classOf[Demo])

  private class TestCall[A, B](call: RemoteCall[A, B], input: A) {
    protected def check(b: B): Unit = ()
    def remote(conn: JsonRpcConnection)(implicit ec: ExecutionContext) =
      call.remote(conn, input).map { b =>
        check(b)
        b
      }
    def methodName = call.methodName
  }

  private def largeMessageLength = 100 * 1024

  def run(options: Options, args: RemainingArgs): Unit = {

    if (args.all.nonEmpty)
      sys.error(s"Unrecognized arguments: ${args.all.mkString(", ")}")

    implicit val ec: ExecutionContext = ExecutionContext.global

    val call: TestCall[_, _] = options.call match {
      case "pid" => new TestCall(Calls.pid, ())
      case "fail" => new TestCall(Calls.fail, ())
      case "large" =>
        new TestCall(Calls.large, Calls.Large(largeMessageLength)) {
          override protected def check(b: Calls.LargeResponse): Unit =
            assert(b.dummy.length == largeMessageLength)
        }
      case other =>
        sys.error(s"Unrecognized method: '$other'")
    }

    val master = Master(
      classOf[Worker].getName,
      Nil,
      Nil
    )

    try {
      master.start()

      for (_ <- 1 to options.count) {
        log.debug(s"Calling remote method ${call.methodName}")
        val start = System.nanoTime()
        val f = call.remote(master.connection)
        val res = Await.result(f, Duration.Inf)
        val end = System.nanoTime()
        log.debug(s"Called remote method ${call.methodName}")

        println(s"Result: $res, duration: ${(end - start) / 1000000.0} ms")
      }
    } finally {
      master.stop()
    }
  }
}

sealed abstract class Demo
