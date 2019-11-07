package io.github.alexarchambault.jsonrpc

import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.{util => ju}
import java.util.concurrent.atomic.AtomicBoolean

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.typesafe.scalalogging.Logger

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

// Large parts of the `run` methods were adapted from
// https://github.com/sbt/sbt/blob/2f31849c64957592d992a55c71b0bfe393166681/main/src/main/scala/sbt/internal/server/NetworkChannel.scala

final class ReaderThread(
  in: InputStream,
  onMessage: JsonRpcMessage => Either[JsonRpcError, Unit],
  write: Array[Byte] => Unit,
  exceptionHandler: PartialFunction[Throwable, Unit] = PartialFunction.empty
) extends Thread("jsonrpc-reader") {
  setDaemon(true)

  import ReaderThread._

  private val running = new AtomicBoolean(true)
  private var contentLength: Int = 0
  private var state: ChannelState = SingleLine

  override def run(): Unit =
    try {
      val buffer = new GrowingByteArray(4096)
      def resetChannelState(): Unit = {
        contentLength = 0
        state = SingleLine
      }
      def tillEndOfLine(): Option[(Int, Int)] = {
        val delimPos = buffer.array.indexOf(delimiter)
        if (delimPos > 0) {
          val chunkLen =
            if (buffer.array(delimPos - 1) == RetByte)
              // remove \r at the end of line.
              delimPos - 1
            else
              delimPos
          Some((chunkLen, delimPos + 1))
        } else
          None // no EOL yet, so skip this turn.
      }

      def tillContentLength(): Int =
        if (contentLength <= buffer.size) {
          val len = contentLength
          resetChannelState()
          len
        } else
          -1

      @tailrec def process(drop: Int = 0): Unit = {
        if (drop > 0)
          buffer.drop(drop)

        // handle un-framing
        state match {
          case SingleLine =>
            tillEndOfLine() match {
              case Some((chunkLen, toDrop)) =>
                if (chunkLen == 0) {
                  buffer.drop(toDrop)
                  // ignore blank line
                } else if (buffer.array(0) == Curly) {
                  // When Content-Length header is not found, interpret the line as JSON message.
                  handleBody(buffer.array, 0, chunkLen)
                  buffer.drop(toDrop)
                  process()
                } else {
                  val str = new String(buffer.array, 0, chunkLen, StandardCharsets.UTF_8).trim
                  buffer.drop(toDrop)
                  if (handleHeader(str)) {
                    state = InHeader
                    process()
                  } else
                    log.error("Got invalid chunk from client: " + str)
                }
              case _ => ()
            }
          case InHeader =>
            tillEndOfLine() match {
              case Some((chunkLen, toDrop)) =>
                if (chunkLen == 0) {
                  buffer.drop(toDrop)
                  state = InBody
                  process()
                } else {
                  val str = new String(buffer.array, 0, chunkLen, StandardCharsets.UTF_8).trim
                  buffer.drop(toDrop)
                  if (handleHeader(str))
                    process()
                  else {
                    log.error("Got invalid header from client: " + str)
                    resetChannelState()
                  }
                }
              case _ => ()
            }
          case InBody =>
            val chunkLen = tillContentLength()
            if (chunkLen >= 0) {
              handleBody(buffer.array, 0, chunkLen)
              buffer.drop(chunkLen)
              process()
            }
        }
      }

      // keep going unless the socket has closed
      var bytesRead = 0
      while (bytesRead != -1 && running.get)
        try {
          if (buffer.size == buffer.array.length)
            buffer.grow(buffer.array.length << 1)
          assert(buffer.array.length > buffer.size)
          bytesRead = in.read(buffer.array, buffer.size, buffer.array.length - buffer.size)
          if (bytesRead > 0)
            buffer.wrote(bytesRead)
          process()
        } catch {
          case _: SocketTimeoutException => // it's ok
        }
    } catch exceptionHandler finally {
      shutdown()
    }

  def shutdown(): Unit =
    running.set(false)

  private def handleBody(buf: Array[Byte], offset: Int, len: Int): Unit = {
    log.debug(s"handleBody(${Try(new String(buf, offset, len))})")
    deserializeJsonMessage(buf, offset, len) match {
      case Right(msg) =>
        log.debug(s"Received $msg")
        onMessage(msg) match {
          case Left(err) =>
            log.debug(s"Error processing $msg", err)
            jsonRpcRespondError(msg.idOpt, err.code, err.message)
          case Right(()) =>
            log.debug(s"Processed $msg")
        }
      case Left(errorDesc) =>
        val msg = s"Got invalid chunk from client (${Try(new String(buf, offset, len, StandardCharsets.UTF_8)).toOption.getOrElse("???")}): " + errorDesc
        jsonRpcRespondError(None, ErrorCodes.ParseError, msg)
    }
  }

  private def handleHeader(str: String): Boolean = {
    log.debug(s"Header: $str")
    if (str.startsWith(ContentLengthPrefix)) {
      val idx = str.indexWhere(!_.isSpaceChar, ContentLengthPrefix.length)
      if (idx >= 0)
        try {
          contentLength = str.substring(idx).toInt
          true
        } catch {
          case _: NumberFormatException =>
            false
        }
      else
        false
    } else
      str.startsWith(ContentTypePrefix)
  }

  private def jsonRpcRespondError(
    idOpt: Option[String],
    code: Long,
    message: String
  ): Unit = {
    val m = JsonRpcMessage.Response.error(idOpt, code, message)
    write(m.serialize)
  }

}

object ReaderThread {

  private final class GrowingByteArray(initialCapacity: Int) {
    private val initCapBuf = Array.ofDim[Byte](initialCapacity)
    private var buf = initCapBuf
    private var size0 = 0
    def array: Array[Byte] =
      buf
    def size: Int =
      size0
    def grow(capacity: Int): Unit =
      if (capacity < array.length) {
        var newCapacity = array.length << 1
        if (newCapacity < capacity)
          newCapacity = capacity
        // FIXME java.io.ByteArrayOutputStream.grow has extra logic for huge capacities here.
        buf = ju.Arrays.copyOf(buf, newCapacity)
      }
    def wrote(length: Int): Unit = {
      assert(size0 + length <= buf.length)
      size0 = size0 + length
    }
    def drop(count: Int): Unit = {
      val count0 = Math.min(count, size0)
      val newSize = size0 - count0
      val shrink = size0 > initialCapacity && newSize <= initialCapacity
      val src = buf
      if (shrink)
        buf = initCapBuf
      System.arraycopy(src, count0, buf, 0, newSize)
      size0 = newSize
    }
  }

  private val log = Logger(classOf[ReaderThread])

  private def delimiter: Byte = '\n'
  private def RetByte: Byte = '\r'
  private val Curly: Byte = '{'
  private val ContentLengthPrefix = "Content-Length:"
  private val ContentTypePrefix = "Content-Type:"

  private sealed abstract class ChannelState extends Product with Serializable
  private case object SingleLine extends ChannelState
  private case object InHeader extends ChannelState
  private case object InBody extends ChannelState

  private final case class Probe(method: Option[String], id: Option[String])
  private object Probe {
    implicit val codec: JsonValueCodec[Probe] =
      JsonCodecMaker.make[Probe](CodecMakerConfig)
  }

  private def deserializeJsonMessage(buf: Array[Byte], offset: Int, len: Int): Either[String, JsonRpcMessage] =
    Try(readFromSubArray[Probe](buf, offset, offset + len)) match {
      case Failure(t) =>
        log.debug("Could not probe message", t)
        Left("Malformed message")
      case Success(check) =>
        val hasMethod = check.method.nonEmpty
        if (hasMethod) {
          val hasId = check.id.nonEmpty
          if (hasId)
            Try(readFromSubArray[JsonRpcMessage.Request](buf, offset, offset + len)) match {
              case Failure(t) =>
                log.debug("Could not decode request", t)
                Left("Malformed request")
              case Success(req) =>
                Right(req)
            }
          else
            Try(readFromSubArray[JsonRpcMessage.Notification](buf, offset, offset + len)) match {
              case Failure(t) =>
                log.debug("Could not decode notification", t)
                Left("Malformed notification")
              case Success(notif) =>
                Right(notif)
            }
        } else
          Try(readFromSubArray[JsonRpcMessage.Response](buf, offset, offset + len)) match {
            case Failure(t) =>
              log.debug("Could not decode response", t)
              Left("Malformed response")
            case Success(resp) =>
              Right(resp)
          }
    }
}
