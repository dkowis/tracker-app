package is.kow.scalatratrackerapp.actors.responders

import scala.util.matching.Regex

trait TextResponder {
  val regexes: Seq[Regex]
}
