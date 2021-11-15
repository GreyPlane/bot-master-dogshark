package org.dogshark

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer}
import akka.stream.BoundedSourceQueue
import io.circe.syntax._
import org.dogshark.BotSupervisor.{ApiSocketConnected, BotProtocol}
import org.dogshark.OneBotProtocol.actionParams._
import org.dogshark.OneBotProtocol.{AnyAction, AnyActionResponse, AnyEvent}
import org.slf4j

object DumbDumb {

  val BOT_ID: String = "dumbdumb"

  def apply(): Behavior[BotProtocol] =
    Behaviors.setup[BotProtocol] { implicit context =>
      Behaviors.withStash(100) { implicit buffer =>
        implicit val log: slf4j.Logger = context.log
        waitingForApiConnection
      }
    }

  private def waitingForApiConnection(implicit log: slf4j.Logger, buffer: StashBuffer[BotProtocol]): Behavior[BotProtocol] =
    Behaviors
      .receiveMessage[BotProtocol] {
        case m: Bark =>
          buffer.stash(m)
          Behaviors.same
        case ApiSocketConnected(queue) => buffer.unstashAll(withApiChannel(queue))
        case _ => Behaviors.ignore
      }

  private def withApiChannel(channel: BoundedSourceQueue[AnyAction])(implicit log: slf4j.Logger): Behavior[BotProtocol] =
    Behaviors
      .receiveMessage[BotProtocol] {
        case AnyActionResponse(data, echo, code, status) => {
          log.info(data.noSpaces)
          Behaviors.same
        }
        case AnyEvent(eventType, body) => {
          log.info(body.noSpaces)
          if (eventType == "message") {
            for {
              message <- body.hcursor.get[String]("raw_message")
              user <- body.hcursor.get[Int]("user_id")
              _ <- Right(channel.offer(AnyAction("send_msg", Some(SendMessage("private", Some(user), None, s"bark!!! -> $message").asJson))))
            } yield ()
          }
          Behaviors.same
        }
        case Bark(to) =>
          channel.offer(AnyAction("send_msg", Some(SendMessage("private", Some(to), None, "bark from dumbdumb!").asJson)))
          Behaviors.same
        case _ => Behaviors.ignore
      }

  final case class Bark(to: Int) extends BotProtocol


}
