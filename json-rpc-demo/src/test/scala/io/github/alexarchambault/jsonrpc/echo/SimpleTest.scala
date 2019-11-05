package io.github.alexarchambault.jsonrpc.demo

import java.nio.file.{Files, Path, Paths}
import java.util.stream.Stream

import utest._

import java.io.File

import scala.collection.JavaConverters._

object SimpleTest extends TestSuite {

  lazy val packDir = sys.props.get("jsonrpc.demo.packDir") match {
    case Some(path) => Paths.get(path).toAbsolutePath
    case None =>
      sys.error("jsonrpc.demo.packDir not set")
  }

  lazy val nativeLauncher = sys.props.get("jsonrpc.demo.nativeLauncher") match {
    case Some(path) => Paths.get(path).toAbsolutePath
    case None =>
      sys.error("jsonrpc.demo.nativeLauncher not set")
  }

  val roundTripCount = 100

  val tests = Tests {
    "jvm" - {
      val launcher = packDir.resolve("bin/demo")
      Predef.assert(Files.isRegularFile(launcher), s"$launcher not found")

      val b = new ProcessBuilder(s"$launcher", s"--count=$roundTripCount")
      b.inheritIO()
      val p = b.start()
      val retCode = p.waitFor()
      assert(retCode == 0)
    }

    "hybrid" - {
      Predef.assert(Files.isRegularFile(nativeLauncher), s"$nativeLauncher not found")

      val libs = packDir.resolve("lib")
      var s: Stream[Path] = null
      val cp = try {
        s = Files.list(libs)
        s.iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .map(_.toString)
          .mkString(File.pathSeparator)
      } finally {
        if (s != null)
          s.close()
      }

      val b = new ProcessBuilder(s"$nativeLauncher", s"-Djava.class.path=$cp", s"--count=$roundTripCount")
      b.inheritIO()
      val p = b.start()
      val retCode = p.waitFor()
      assert(retCode == 0)
    }
  }

}