package org.dogshark

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import com.typesafe.config.{Config, ConfigFactory}
import org.dogshark.BotSupervisor.Terminate
import org.scalatest.wordspec.AnyWordSpecLike

class BotSupervisorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  val config: Config = ConfigFactory.load()

  "BotSupervisor" should {
    "drive the dumbdumb" in {
      val dumbdumb = DumbDumb.apply()
      val dogshark = testKit.spawn(BotSupervisor(List((DumbDumb.BOT_ID, dumbdumb)), config.getConfig("bot")))
      Thread.sleep(10000000)
      dogshark ! Terminate
      Thread.sleep(1000)
    }
  }
}
