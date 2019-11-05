package io.github.alexarchambault.jsonrpc.demo

import com.typesafe.scalalogging.Logger
import io.github.alexarchambault.jsonrpc.Server

import scala.concurrent.ExecutionContext

object Worker {
  private val log = Logger(classOf[Worker])
  def main(args: Array[String]): Unit = {
    log.debug("Worker starting")
    implicit val ec = ExecutionContext.global
    lazy val server: Server = Server(Calls.calls ++ Seq(Calls.Special.serverStop(server)))
    server.start()
    server.join()
    log.debug("Worker exiting")
  }
}

sealed abstract class Worker
