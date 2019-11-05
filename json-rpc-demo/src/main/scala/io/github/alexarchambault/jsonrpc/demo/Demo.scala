package io.github.alexarchambault.jsonrpc.demo

import caseapp._
import com.typesafe.scalalogging.Logger
import io.github.alexarchambault.jsonrpc.Master

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration

object Demo extends CaseApp[Options] {
  private val log = Logger(classOf[Demo])
  def run(options: Options, args: RemainingArgs): Unit = {

    if (args.all.nonEmpty)
      sys.error(s"Unrecognized arguments: ${args.all.mkString(", ")}")

    implicit val ec: ExecutionContext = ExecutionContext.global

    val call = options.call match {
      case "pid" => Calls.pid
      case "fail" => Calls.fail
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
        val f = call.remote(master.connection, Calls.Empty())
        val remoteRes = Await.result(f, Duration.Inf)
        log.debug(s"Called remote method ${call.methodName}")

        log.debug(s"Calling local method ${call.methodName}_")
        val f0 = call.local(master.connection, Calls.Empty())
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
