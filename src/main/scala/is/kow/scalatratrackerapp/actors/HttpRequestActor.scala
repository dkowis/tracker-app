package is.kow.scalatratrackerapp.actors

import akka.actor.{Actor, ActorLogging, Props}
import com.mashape.unirest.http.async.Callback
import com.mashape.unirest.http.exceptions.UnirestException
import com.mashape.unirest.http.{HttpResponse, JsonNode, Unirest}
import is.kow.scalatratrackerapp.actors.HttpRequestActor.{GetRequest, PostRequest, RequestFailed, Response}
import org.apache.http.HttpHost

object HttpRequestActor {
  def props(proxyInfo: Option[HttpHost]) = Props(new HttpRequestActor(proxyInfo))


  sealed trait HttpRequest

  case class GetRequest(url: String, headers: Map[String, String] = Map.empty[String, String]) extends HttpRequest

  case class PostRequest(url: String,
                         headers: Map[String, String] = Map.empty[String, String],
                         payload: String) extends HttpRequest

  case class ProxyInfo(host: String, port: Int)

  case class RequestFailed(request: HttpRequest, e: Option[UnirestException])
  //Encapsulate the java class, so that type erasure doesn't happen
  case class Response(response: HttpResponse[String])

}

class HttpRequestActor(proxyInfo: Option[HttpHost] = None) extends Actor with ActorLogging {

  log.debug("This is a test message " * 10)
  if (proxyInfo.isDefined) {
    val proxy = proxyInfo.get
    Unirest.setProxy(proxy)
  }

  override def postStop(): Unit = {
    Unirest.shutdown()
  }

  import scala.collection.JavaConverters._

  def receive = {
    case get: GetRequest =>
      val senderRef = sender()
      //TODO: asObjectAsync would be nicer, but types?
      log.debug(s"Making async get request for ${get.url}\nHeaders: ${get.headers}")
      Unirest.get(get.url).headers(get.headers.asJava).asStringAsync(new Callback[String] {
        override def failed(e: UnirestException): Unit = {
          //Explosion time, need to encapsulate with a request failed
          senderRef ! RequestFailed(get, Some(e))
        }

        override def completed(response: HttpResponse[String]): Unit = {
          //Completed, need to send the HttpResponse back to who asked for it
          senderRef ! Response(response)
        }

        override def cancelled(): Unit = {
          //Perhaps send to myself that I've been cancelled?
          senderRef ! RequestFailed(get, None)
        }
      })

    case post: PostRequest =>
      val senderRef = sender()
      log.debug(s"Making post request to: ${post.url}\nHeaders: ${post.headers}\nBody:\n${post.payload}")
      Unirest.post(post.url).headers(post.headers.asJava).body(post.payload).asStringAsync(new Callback[String] {
        override def failed(e: UnirestException): Unit = {
          senderRef ! RequestFailed(post, Some(e))
        }

        override def completed(response: HttpResponse[String]): Unit = {
          senderRef ! Response(response)
        }

        override def cancelled(): Unit = {
          senderRef ! RequestFailed(post, None)
        }
      })
  }
}
