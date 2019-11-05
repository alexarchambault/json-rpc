package io.github.alexarchambault.jsonrpc.demo

import caseapp._
import caseapp.core.help.Help

final case class Options(
  call: String = "pid",
  count: Int = 1
)

object Options {
  implicit val parser = Parser[Options]
  implicit val help = Help[Options]
}