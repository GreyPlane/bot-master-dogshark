package org.dogshark

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.BoundedSourceQueue
import io.circe.syntax._
import org.dogshark.BotSupervisor.{ApiSocketConnected, BotCommand, BotProtocol}
import org.dogshark.OneBotProtocol.actionParams._
import org.dogshark.OneBotProtocol.{AnyAction, AnyActionResponse, AnyEvent}
import shapeless._

object DumbDumb {

  val BOT_ID: String = "dumbdumb"

  private object echo extends Poly1 {
    implicit def caseAnyActionResponse = at[AnyActionResponse](actionResponse => {
      println(actionResponse.data.noSpaces)
      Behaviors.same[BotCommand]
    })

    implicit def caseAnyEvent(implicit apiChannel: BoundedSourceQueue[AnyAction]) = at[AnyEvent] {
      case AnyEvent(eventType, body) => {
        println(body.noSpaces)
        if (eventType == "message") {
          for {
             message <- body.hcursor.get[String]("raw_message")
             user <- body.hcursor.get[Int]("user_id")
             _ <- Right(apiChannel.offer(AnyAction("send_msg", Some(SendMessage("private", Some(user), None, s"bark!!! -> $message").asJson))))
          } yield ()
        }
        Behaviors.same[BotCommand]
      }
    }

    implicit def caseDefault[T] = at[T] { _ => Behaviors.ignore[BotCommand] }
  }
  private val waitingForApiConnection: Behavior[BotCommand] = Behaviors
    .receiveMessage(_.select[BotProtocol].map { case ApiSocketConnected(queue) => withApiChannel(queue) }.getOrElse(Behaviors.ignore))

  def apply(): Behavior[BotCommand] = waitingForApiConnection

  private def withApiChannel(implicit channel: BoundedSourceQueue[AnyAction]): Behavior[BotCommand] = Behaviors
    .receiveMessage(_.fold(echo))


}
