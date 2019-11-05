import sbt._
import sbt.Keys._

object Settings {

  def scala212 = "2.12.10"
  def scala213 = "2.13.1"

  lazy val shared = Def.settings(
    scalaVersion := scala213,
    crossScalaVersions := Seq(scala213, scala212),
    organization := "io.github.alexarchambault"
  )
}
