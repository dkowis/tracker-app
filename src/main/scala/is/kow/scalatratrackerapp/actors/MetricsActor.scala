package is.kow.scalatratrackerapp.actors

import java.util

import akka.actor.{Actor, ActorLogging, Props}
import is.kow.scalatratrackerapp.actors.MetricsActor.{GaugeMetric, RequestMetrics}
import nl.grons.metrics.scala.{DefaultInstrumented, Gauge}

object MetricsActor {
  def props = Props[MetricsActor]

  //TODO: would this break *everything*?
   case class GaugeMetric[T](name:String, value:T)

  case object RequestMetrics
}

class MetricsActor extends Actor with ActorLogging with DefaultInstrumented {


  //Something to hold/create the gauges by their ID
  //Scala gauge take types metrics :|
  //private val gaugeCache:util.HashMap[String, Gauge] = new util.HashMap[String, Gauge]()


  override def receive = {
    case RequestMetrics =>
      //Just send back all the metrics like a crazy person
      //There's a JSON marshaller in the other route and it knows how to unpack it
      sender ! metrics.registry
  }
}
