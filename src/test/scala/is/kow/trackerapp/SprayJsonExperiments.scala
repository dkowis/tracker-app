package is.kow.trackerapp

import is.kow.scalatratrackerapp.actors.pivotal.PivotalResponses.PivotalStory
import org.scalatest.{FunSpec, Matchers}
import spray.json.{DeserializationException, JsonParser}

class SprayJsonExperiments extends FunSpec with Matchers {

  import is.kow.scalatratrackerapp.actors.pivotal.PivotalJsonProtocol._

  describe("Parsing JSON should work") {
    it("fails somehow?"){
      val string = """
        |{
        |"thisjson":"isn't valid"
        |}
      """.stripMargin

      a [DeserializationException] should be thrownBy JsonParser(string).convertTo[PivotalStory]
    }
  }

}
