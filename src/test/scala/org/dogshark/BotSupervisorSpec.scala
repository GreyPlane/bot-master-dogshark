package org.dogshark

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, FishingOutcomes, ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.{Config, ConfigFactory}
import org.dogshark.BotSupervisor.{BotProtocol, Forward, Terminate}
import org.dogshark.DumbDumb.Bark
import org.dogshark.OneBotProtocol.AnyEvent
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt

class BotSupervisorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  val config: Config = ConfigFactory.load()

  "BotSupervisor" should {
    "drive the dumbdumb" in {
      val me = 709327148
      val probe = TestProbe[BotProtocol]()
      val dumbdumb  = Behaviors.monitor[BotProtocol](probe.ref, DumbDumb())
      val botConfig = config.getConfig("bot")
      val dogshark = testKit.spawn(BotSupervisor(List((DumbDumb.BOT_ID, dumbdumb)), botConfig))
      val barkToMe = Bark(me)
      dogshark ! Forward(DumbDumb.BOT_ID, barkToMe)
      probe.fishForMessage(20.seconds) {
        case AnyEvent(eventType, body) => if (eventType == "message") FishingOutcomes.complete else FishingOutcomes.fail("wrong event")
        case Bark(to) => if (to == me) FishingOutcomes.complete else FishingOutcomes.fail("wrong bark")
        case _ => FishingOutcomes.continueAndIgnore
      }
      dogshark ! Terminate
    }
  }
}
