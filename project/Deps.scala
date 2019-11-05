import sbt._
import sbt.Keys.scalaVersion

object Deps {
  private def jsoniterScalaVersion = "1.1.0"

  def caseApp = "com.github.alexarchambault" %% "case-app" % "2.0.0-M9"
  def ipcSocket = "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.0.0"
  def jsoniterScala = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterScalaVersion
  def jsoniterScalaMacros = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterScalaVersion
  def logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
  def scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
  def svm = "com.oracle.substratevm" % "svm" % "19.2.0.1"
  def utest = "com.lihaoyi" %% "utest" % "0.7.1"
}
