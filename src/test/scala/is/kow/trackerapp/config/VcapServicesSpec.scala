package is.kow.trackerapp.config

import is.kow.scalatratrackerapp.config.VcapService
import org.scalatest.{FunSpec, Matchers}
import spray.json.JsonParser

import scala.None
import scala.io.Source

class VcapServicesSpec extends FunSpec with Matchers {

  describe("With a pile of vcap services JSON") {
    val vcapServices = Source.fromResource("vcap_services.json").mkString
    it("can find the database by name") {
      import is.kow.scalatratrackerapp.config.VcapServicesFormat._
      val services = JsonParser(vcapServices).convertTo[Map[String, List[VcapService]]]

      val trackerDb = services.flatMap { case (key, value) =>
        value
      }.find(service => {
        service.name == "io1-tracker-app-db"
      })

      trackerDb shouldNot equal(None)

      trackerDb.get.credentials("hostname").convertTo[String] should be("2.2.2.2")
      trackerDb.get.credentials("port").convertTo[Int] should be(3306)
    }
  }
}
