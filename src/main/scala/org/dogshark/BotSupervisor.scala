package org.dogshark


import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{ActorAttributes, BoundedSourceQueue, Supervision}
import com.typesafe.config.Config
import io.circe.parser._
import io.circe.syntax._
import org.dogshark.OneBotProtocol._
import shapeless._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}


object BotSupervisor {

  type BotCommand = AnyEvent :+: AnyActionResponse :+: BotProtocol :+: CNil

  def apply(botPairs: List[(String, Behavior[BotCommand])], botConfig: Config): Behavior[SupervisorCommand] =
    Behaviors.supervise[SupervisorCommand] {
      Behaviors.setup { implicit context =>
        implicit val system: ActorSystem[Nothing] = context.system
        implicit val ec: ExecutionContextExecutor = context.executionContext
        if (botPairs.map(_._1).toSet.size != botPairs.size) throw new IllegalArgumentException("bot id must be unique")
        val botRefs = botPairs.map {
          case (id, behavior) =>
            val botRef = context.spawn(behavior, id)
            context.watch(botRef)
            (id, botRef)
        }.toMap
        val socket = botConfig.getString("socket")
        val ((actionQueue, apiSocketUpgrade), apiSocketClose) = setupApiStream(socket, botRefs)
        context.pipeToSelf(apiSocketUpgrade) { tryUpgrade =>
          tryUpgrade.map(upgrade => if (upgrade.response.status == StatusCodes.SwitchingProtocols) ConnectionSuccess(actionQueue) else ConnectionFailed).getOrElse(ConnectionFailed)
        }
        val (eventSocketUpgrade, eventSocketClose) = setupEventStream(socket, botRefs)
        Behaviors
          .receiveMessage[SupervisorCommand] {
            case ConnectionSuccess(queue) =>
              botRefs.foreach { case (_, ref) => ref ! Coproduct[BotCommand](ApiSocketConnected(queue)) }
              Behaviors.same;
            case Terminate =>
              actionQueue.complete()
              import cats.implicits._
              Await.result(List(apiSocketClose, eventSocketClose).sequence, 10.seconds)
              Behaviors.stopped
          }
      }
    }.onFailure[DeathPactException](SupervisorStrategy.restart)

  private def setupApiStream(socket: String, botRefs: Map[String, ActorRef[BotCommand]])
                            (implicit system: ActorSystem[Nothing], ec: ExecutionContext) = {
    val replyToBot: Sink[AnyActionResponse, Future[Done]] = Sink.foreach {
      case res@AnyActionResponse(_, maybeBotId, _, _) => for {
        botId <- maybeBotId
        actorRef <- botRefs.get(botId)
      } yield actorRef ! Coproduct[BotCommand](res)
    }

    val apiSocket = Http().webSocketClientFlow(WebSocketRequest(s"$socket/api"))

    Source.queue[AnyAction](100)
      .map(action => TextMessage(action.asJson.noSpaces))
      .viaMat(apiSocket)(Keep.both)
      .mapAsync(4) {
        case t: TextMessage => t.toStrict(10.seconds).map(tt => parse(tt.getStrictText).flatMap(_.as[AnyActionResponse]).getOrElse(throw new IllegalArgumentException("")))
      }
      .toMat(replyToBot)(Keep.both)
      .withAttributes(ActorAttributes.supervisionStrategy { e: Throwable => system.log.error("Exception in stream", e); Supervision.Stop })
      .run()
  }

  private def setupEventStream(socket: String, botRefs: Map[String, ActorRef[BotCommand]])
                              (implicit system: ActorSystem[Nothing], ec: ExecutionContext) = {

    val broadcastToBot: Sink[Message, Future[Done]] = Sink.foreach {
      case message: TextMessage.Strict => {
        val json = parse(message.getStrictText).getOrElse(throw new IllegalArgumentException(""))
        val eventType = json.hcursor.get[String]("post_type").getOrElse("unknown")
        if (eventType != "meta_event") {
          botRefs.values.foreach {
            _ ! Coproduct[BotCommand](AnyEvent(eventType, json))
          }
        } else {
          system.log.debug(s"receive meta event:\n${json.noSpaces}")
        }
      }
      case _ => system.log.warn("unknown message from event socket")
    }

    Http().singleWebSocketRequest(WebSocketRequest(s"$socket/event"), Flow.fromSinkAndSourceMat(broadcastToBot, Source.maybe)(Keep.left))
  }

  sealed trait BotProtocol

  sealed trait SupervisorCommand

  case class ApiSocketConnected(queue: BoundedSourceQueue[AnyAction]) extends BotProtocol

  private case class ConnectionSuccess(queue: BoundedSourceQueue[AnyAction]) extends SupervisorCommand

  case object Terminate extends SupervisorCommand

  private case object ConnectionFailed extends SupervisorCommand
}