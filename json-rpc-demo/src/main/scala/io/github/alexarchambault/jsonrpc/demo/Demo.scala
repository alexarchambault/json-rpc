package io.github.alexarchambault.jsonrpc.demo

import caseapp._
import com.typesafe.scalalogging.Logger
import io.github.alexarchambault.jsonrpc.{JavaCall, JsonRpcConnection, Master}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration

object Demo extends CaseApp[Options] {
  private val log = Logger(classOf[Demo])

  private class TestCall[A, B](call: JavaCall[A, B], input: A) {
    protected def check(b: B): Unit = ()
    def remote(conn: JsonRpcConnection)(implicit ec: ExecutionContext) =
      call.remote(conn, input).map { b =>
        check(b)
        b
      }
    def local(conn: JsonRpcConnection)(implicit ec: ExecutionContext) =
      call.local(conn, input).map { b =>
        check(b)
        b
      }
    def methodName = call.methodName
  }

  def run(options: Options, args: RemainingArgs): Unit = {

    if (args.all.nonEmpty)
      sys.error(s"Unrecognized arguments: ${args.all.mkString(", ")}")

    implicit val ec: ExecutionContext = ExecutionContext.global

    val call: TestCall[_, _] = options.call match {
      case "pid" => new TestCall(Calls.pid, Calls.Empty())
      case "fail" => new TestCall(Calls.fail, Calls.Empty())
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
        val start = System.nanoTime()
        log.debug(s"Calling remote method ${call.methodName}")
        val f = call.remote(master.connection)
        val remoteRes = Await.result(f, Duration.Inf)
        log.debug(s"Called remote method ${call.methodName}")

        log.debug(s"Calling local method ${call.methodName}_")
        val f0 = call.local(master.connection)
        val localRes = Await.result(f0, Duration.Inf)
        log.debug(s"Called local method ${call.methodName}")
        val end = System.nanoTime()

        println(s"Local: $localRes, remote: $remoteRes, duration: ${(end - start) / 1000000.0} ms")
      }
    } finally {
      master.stop()
    }
  }
}

sealed abstract class Demo
