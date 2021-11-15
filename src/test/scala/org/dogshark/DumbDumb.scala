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
  type DumbDumbProtocol = DumbDumbCommand :+: BotCommand :+: CNil
  val BOT_ID: String = "dumbdumb"

  private val waitingForApiConnection: Behavior[DumbDumbProtocol] = Behaviors
    .receiveMessage(_.select[BotCommand].flatMap(_.select[BotProtocol]).map { case ApiSocketConnected(queue) => withApiChannel(queue) }.getOrElse(Behaviors.ignore))

  def apply(): Behavior[DumbDumbProtocol] = waitingForApiConnection


  sealed trait DumbDumbCommand

  private case object Bark extends DumbDumbCommand

  private object echo extends Poly1 {
    implicit def caseAnyActionResponse = at[AnyActionResponse](actionResponse => {
      println(actionResponse.data.noSpaces)
      Behaviors.same[DumbDumbProtocol]
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
        Behaviors.same[DumbDumbProtocol]
      }
    }

    implicit def caseBark(implicit apiChannel: BoundedSourceQueue[AnyAction]) = at[DumbDumbCommand] {
      case Bark => {
        Behaviors.same[DumbDumbProtocol]
      }
    }

    implicit def caseDefault[T] = at[T] { _ => Behaviors.ignore[DumbDumbProtocol] }
  }

  private def withApiChannel(implicit channel: BoundedSourceQueue[AnyAction]): Behavior[DumbDumbProtocol] = Behaviors.receiveMessage(_.fold(echo))


}

object DumbDumbCat {
  sealed trait DumbDumbCatCommand
  case object Meow extends DumbDumbCatCommand
  type DumbDumbCatProtocol = DumbDumbCatCommand :+: BotCommand :+: CNil

  object dumbEcho extends Poly1 {
    implicit val meow = at[DumbDumbCatCommand] {
      case Meow => {
        println("meow")
        Behaviors.same[DumbDumbCatProtocol]
      }
    }
    implicit def default[T] = at[T] { _ => Behaviors.ignore[DumbDumbCatProtocol] }
  }

  def apply(): Behavior[DumbDumbCatProtocol] = Behaviors.receiveMessage(_.fold(dumbEcho))
}