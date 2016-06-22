package actors

import akka.actor.{Actor, Props}


object TrackerInfo {
  def props = Props[TrackerInfo]
}

class TrackerInfo extends Actor {


  def receive = {
    ???
  }
}
