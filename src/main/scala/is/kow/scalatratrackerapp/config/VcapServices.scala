package is.kow.scalatratrackerapp.config

import spray.json.{DefaultJsonProtocol, JsValue}

case class VcapService(
                        credentials: Map[String, JsValue],
                        label: String,
                        name: String,
                        tags: List[String]
                    )

object VcapServicesFormat extends DefaultJsonProtocol {

  implicit val VcapServiceFormat = jsonFormat4(VcapService)
}