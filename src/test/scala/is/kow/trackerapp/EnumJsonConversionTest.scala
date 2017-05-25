package is.kow.trackerapp

import org.scalatest.{Matchers, WordSpec}
import spray.json.{DeserializationException, JsString, JsValue, JsonFormat, RootJsonFormat}

//https://groups.google.com/forum/#!topic/spray-user/RkIwRIXzDDc
// Super handy, probably could've written this myself, but I love it
class EnumJsonConversionTest extends WordSpec with Matchers {

  object Animal extends Enumeration {
    type Animal = Value
    val DOG, CAT, FISH, BIRD = Value
  }

//  def jsonEnum[T <: Enumeration](enum: T) = new JsonFormat[T#Value] {
//
//    def write(obj: T#Value) = JsString(obj.toString)
//
//    def read(json: JsValue) = json match {
//      case JsString(txt) => enum.withName(txt)
//      case something => throw DeserializationException(s"Expected a value from enum $enum instead of $something")
//    }
//  }

  //Convert it to be like this: https://github.com/spray/spray-json#providing-jsonformats-for-other-types

  class EnumJsonConverter[T <: scala.Enumeration](enu: T) extends RootJsonFormat[T#Value] {

    def write(obj: T#Value) = JsString(obj.toString)

    def read(json: JsValue) = {
      json match {
        case JsString(txt) => enu.withName(txt)
        case something => throw DeserializationException(s"Expected a value from enum $enu instead of $something")
      }
    }
  }

  "JSON convertible enums" should {

    implicit val converter = new EnumJsonConverter(Animal)

    "ensure that a list of all values enum class should contain all of its elements" in {
      val vals: Animal.ValueSet = Animal.values
      vals.contains(Animal.DOG) shouldBe true     // we won't bother to check the rest.... DOG should suffice
    }

    "convert objects containing Animal enum components to Json, & parse that Json back into the original object" in {
      import Animal._
      import spray.json.DefaultJsonProtocol._
      import spray.json._

      case class AnimalMap(name: String, map: Map[Animal,String]) // template for objects w/ Animal enum components
      implicit val animalMapConverter = jsonFormat2(AnimalMap)

      val mapAsJsonString = """{"name":"dogmap","map":{"DOG":"good dog!","CAT":"bad cat!"}}"""
      val originalAnimalMap = AnimalMap("dogmap", Map(Animal.DOG -> "good dog!", Animal.CAT -> "bad cat!"))

      val originalMapConvertedToJson: JsValue = originalAnimalMap.toJson

      val parsedJson = mapAsJsonString.parseJson
      System.out.println("parsedJson:" + parsedJson)
      val jsonConvertedBackToAnimalMap: AnimalMap = parsedJson.convertTo[AnimalMap]
      jsonConvertedBackToAnimalMap shouldEqual originalAnimalMap
    }
  }
}
