package controllers

import javax.inject._

import akka.actor.ActorRef
import play.api.Configuration
import play.api.mvc._
import services.Pivotal

import scala.concurrent.ExecutionContext

/**
  * This controller creates an `Action` to handle HTTP requests to the
  * application's home page.
  */
@Singleton
class TrackerController @Inject()(
                                   //Ask for my actor ref, so that the actor will be fired up
                                   @Named("slack-bot-actor") slackBotActor: ActorRef,
                                   config: Configuration,
                                   pivotal: Pivotal)(implicit context: ExecutionContext) extends Controller {

  //By injecting it, the actor should be ready to go
  //I don't think I need any actual items in here!
}
