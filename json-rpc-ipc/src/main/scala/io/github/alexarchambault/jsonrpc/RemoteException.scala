package io.github.alexarchambault.jsonrpc

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

final class RemoteException(remoteException: Seq[(String, Seq[String])])
  extends Exception("Remote exception:\n" + RemoteException.Repr(remoteException).format)

object RemoteException {

  final case class Repr(exception: Seq[(String, Seq[String])]) {
    def instance(): RemoteException =
      new RemoteException(exception)
    def format: String = {
      val nl = System.getProperty("line.separator")
      val b = new StringBuilder
      for ((m, l) <- exception) {
        b.append("(remote) ")
        b.append(m)
        b.append(nl)
        for (l0 <- l) {
          b.append("\tat ")
          b.append(l0)
          b.append(nl)
        }
      }
      b.append("(local)")
      b.toString
    }
  }

  object Repr {
    def apply(t: Throwable): Repr = {

      def helper(t: Throwable): Stream[(String, Seq[String])] =
        if (t == null) Stream()
        else {
          val message = t.toString
          val stackTrace = t.getStackTrace.map(_.toString).toSeq
          (message, stackTrace) #:: helper(t.getCause)
        }

      Repr(helper(t).toVector)
    }

    implicit val codec: JsonValueCodec[Repr] =
      JsonCodecMaker.make(CodecMakerConfig)
  }
}
