package io.github.alexarchambault.jsonrpc

import java.io.{IOException, OutputStream}
import java.util.concurrent.LinkedBlockingQueue

import com.typesafe.scalalogging.Logger

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Try}

final class WriterThread(out: OutputStream) extends Thread("jsonrpc-writer") {
  setDaemon(true)

  import WriterThread._

  @volatile private var queue = new LinkedBlockingQueue[Element]

  override def run(): Unit =
    try {
      val queue0 = queue
      if (queue0 != null) {
        var elem: Element = null
        while ( {
          elem = queue0.take()
          elem != End
        }) {
          val (data, promise) = elem match {
            case Data(b, p) => (b, p)
            case End => sys.error("Can't happen")
          }
          val res = Try {
            out.write(data)
            out.flush()
          }
          promise.complete(res)
        }

        while ( {
          elem = queue0.poll()
          elem != null
        })
          elem match {
            case Data(_, p) =>
              p.complete(Failure(new IOException("Connection is closed")))
            case End =>
          }
      }
    } catch {
      case t: Throwable =>
        log.error("Caught exception", t)
    }

  def write(b: Array[Byte]): Future[Unit] = {
    // FIXME Pretty sure the mechanism to fail this when the connection is closed is flaky
    // (and could under some conditions accept some messages, while not writing them, nor failing them)
    // Not sure I want to resort to e.g. mutexes to address that…
    val queue0 = queue
    if (queue0 == null)
      Future.failed(new IOException("Connection is closed"))
    else {
      val p = Promise[Unit]()
      queue0.add(Data(b, p))
      p.future
    }
  }

  def shutdown(): Unit = {
    val queue0 = queue
    if (queue0 != null) {
      queue = null
      queue0.add(End)
      out.close()
    }
  }
}

object WriterThread {
  private val log = Logger(classOf[WriterThread])

  private sealed abstract class Element extends Product with Serializable
  private case object End extends Element
  private final case class Data(data: Array[Byte], p: Promise[Unit]) extends Element
}
