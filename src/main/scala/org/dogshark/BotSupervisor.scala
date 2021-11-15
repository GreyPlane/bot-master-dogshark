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
import shapeless.ops.coproduct
import shapeless.ops.hlist

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor, Future}


object BotSupervisor {

  type BotCommand = AnyEvent :+: AnyActionResponse :+: BotProtocol :+: CNil

  def apply[T <: Coproduct, L <: HList](botPairs: L, botConfig: Config)
                           (implicit ev$1: coproduct.Inject[T, BotCommand], ev$2: hlist.ToTraversable.Aux[L, List, (String, Behavior[T])]): Behavior[SupervisorCommand] =
    Behaviors.supervise[SupervisorCommand] {
      Behaviors.setup { implicit context =>
        implicit val system: ActorSystem[Nothing] = context.system
        implicit val ec: ExecutionContextExecutor = context.executionContext
        val botList = botPairs.toList
        if (botList.map(_._1).toSet.size != botList.size) throw new IllegalArgumentException("bot id must be unique")
        val botRefs = botList.map {
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
              botRefs.foreach { case (_, ref) => ref ! Coproduct[T](Coproduct[BotCommand](ApiSocketConnected(queue))) }
              Behaviors.same;
            case Terminate =>
              actionQueue.complete()
              import cats.implicits._
              Await.result(List(apiSocketClose, eventSocketClose).sequence, 10.seconds)
              Behaviors.stopped
            case ConnectionFailed => Behaviors.stopped
            case _ => Behaviors.ignore
          }
      }
    }.onFailure[DeathPactException](SupervisorStrategy.restart)

  private def setupApiStream[T <: Coproduct](socket: String, botRefs: Map[String, ActorRef[T]])
                            (implicit system: ActorSystem[Nothing], ev$1: coproduct.Inject[T, BotCommand]) = {
    val replyToBot: Sink[Message, Future[Done]] = Sink.foreach {
      case message: TextMessage.Strict => for {
        json <- parse(message.getStrictText).toOption
        res <- json.as[AnyActionResponse].toOption
        botId <- res.echo
        actorRef <- botRefs.get(botId)
      } yield actorRef ! Coproduct[T](Coproduct[BotCommand](res))
      case _ => system.log.warn("unknown message from api socket")
    }

    val apiSocket = Http().webSocketClientFlow(WebSocketRequest(s"$socket/api"))

    Source.queue[AnyAction](100)
      .map(action => TextMessage(action.asJson.noSpaces))
      .viaMat(apiSocket)(Keep.both)
      .toMat(replyToBot)(Keep.both)
      .withAttributes(ActorAttributes.supervisionStrategy { e: Throwable => system.log.error("Exception in stream", e); Supervision.Stop })
      .run()
  }

  private def setupEventStream[T <: Coproduct](socket: String, botRefs: Map[String, ActorRef[T]])
                              (implicit system: ActorSystem[Nothing], ev$1: coproduct.Inject[T, BotCommand]) = {

    val broadcastToBot: Sink[Message, Future[Done]] = Sink.foreach {
      case message: TextMessage.Strict => {
        val json = parse(message.getStrictText).getOrElse(throw new IllegalArgumentException(""))
        val eventType = json.hcursor.get[String]("post_type").getOrElse("unknown")
        if (eventType != "meta_event") {
          botRefs.values.foreach {
            _ ! Coproduct[T](Coproduct[BotCommand](AnyEvent(eventType, json)))
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