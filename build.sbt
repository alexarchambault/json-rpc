
inThisBuild(List(
  organization := "io.github.alexarchambault.json-rpc",
  homepage := Some(url("https://github.com/alexarchambault/json-rpc")),
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      "alexarchambault",
      "Alexandre Archambault",
      "",
      url("https://github.com/alexarchambault")
    )
  )
))


lazy val Deps = new {
  private def jsoniterScalaVersion = "2.0.1"

  def caseApp = "com.github.alexarchambault" %% "case-app" % "2.0.0-M9"
  def ipcSocket = "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.0.0"
  def jsoniterScala = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterScalaVersion
  def jsoniterScalaMacros = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterScalaVersion
  def logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
  def scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
  def svm = "com.oracle.substratevm" % "svm" % "19.2.0.1"
  def utest = "com.lihaoyi" %% "utest" % "0.7.1"
}

lazy val shared = Def.settings(
  sonatypeProfileName := "io.github.alexarchambault"
)

lazy val `json-rpc` = project
  .settings(
    shared,
    libraryDependencies ++= Seq(
      Deps.jsoniterScala,
      Deps.jsoniterScalaMacros % Provided,
      Deps.scalaLogging
    )
  )

lazy val `ipc-socket-svm` = project
  .settings(
    shared,
    libraryDependencies += Deps.svm % Provided
  )

lazy val `json-rpc-ipc` = project
  .dependsOn(`ipc-socket-svm`, `json-rpc`)
  .settings(
    shared,
    libraryDependencies ++= Seq(
      Deps.ipcSocket,
      Deps.jsoniterScalaMacros % Provided
    )
  )

lazy val `json-rpc-demo` = project
  .dependsOn(`json-rpc-ipc`)
  .enablePlugins(GraalVMNativeImagePlugin, PackPlugin)
  .settings(
    shared,
    libraryDependencies ++= Seq(
      Deps.caseApp,
      Deps.jsoniterScalaMacros % Provided,
      Deps.logback,
      Deps.svm % Provided,
      Deps.utest % Test
    ),
    testFrameworks += new TestFramework("utest.runner.Framework"),
    mainClass.in(Compile) := Some("io.github.alexarchambault.jsonrpc.demo.Demo"),
    graalVMNativeImageOptions ++= Seq(
      "--no-server",
      "--no-fallback",
      "--report-unsupported-elements-at-runtime",
      "--allow-incomplete-classpath",
      "--initialize-at-build-time=scala.Function1",
      "--initialize-at-build-time=scala.Function2",
      "--initialize-at-build-time=scala.Symbol",
      "-H:+ReportExceptionStackTraces"
    ),
    fork.in(Test) := true,
    javaOptions.in(Test) ++= Seq(
      "-Djsonrpc.demo.packDir=" + pack.value.getAbsolutePath,
      "-Djsonrpc.demo.nativeLauncher=" + packageBin.in(GraalVMNativeImage).value.getAbsolutePath
    )
  )

lazy val `json-rpc-root` = project
  .in(file("."))
  .aggregate(
    `ipc-socket-svm`,
    `json-rpc`,
    `json-rpc-ipc`,
    `json-rpc-demo`
  )
  .settings(
    shared,
    skip.in(publish) := true
  )
