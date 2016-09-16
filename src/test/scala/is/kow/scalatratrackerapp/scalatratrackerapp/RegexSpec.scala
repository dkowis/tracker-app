package is.kow.scalatratrackerapp.scalatratrackerapp

import org.scalatest.{FunSpec, Matchers}


class RegexSpec extends FunSpec with Matchers {
  val assignToRegex = "(?i).*assignTo:\\s+<@(\\w+)>".r
  val testString = "herp a derp AssignTo: <@U1KBZ7CVB>"

  describe("Trying to regex stuff") {
    it("matches") {

      val assignToUserName: Option[String] = testString match {
        case assignToRegex(username) =>
          Some(username)
        case _ =>
          None
      }

      assignToUserName shouldNot be(None)

      assignToUserName.get should be("U1KBZ7CVB")

    }

    it("replaces the right part of the string") {
      val newString = testString.replaceAll("(?i)\\s*assignto:\\s+<@\\w+>.*", "")

      newString should be("herp a derp")
    }
  }

}
