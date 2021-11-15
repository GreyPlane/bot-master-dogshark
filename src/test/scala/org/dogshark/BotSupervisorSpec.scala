package org.dogshark

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import akka.actor.typed.Behavior
import com.typesafe.config.{Config, ConfigFactory}
import org.dogshark.BotSupervisor.{BotCommand, Terminate}
import org.dogshark.DumbDumb.DumbDumbProtocol
import org.dogshark.DumbDumbCat.DumbDumbCatProtocol
import org.scalatest.wordspec.AnyWordSpecLike
import shapeless._
import shapeless.ops.adjoin.Adjoin
import shapeless.ops.coproduct._

class BotSupervisorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  val config: Config = ConfigFactory.load()

  type T1 = List[Int :+: CNil]
  type T2 = List[String :+: CNil]

  type TT = List[Long :+: T1 :+: T2 :+: CNil]
  val t : TT = ???
  val tt = t.map(_.select[T1].map(_.map(_.select[Int])))

  "BotSupervisor" should {
    "drive the dumbdumb" in {
      val dumbdumb = DumbDumb.apply()
      val dumbCat = DumbDumbCat.apply()
//      val dumbdumbCat: Behavior[String :+: BotCommand :+: CNil] = ???
      type HBots = (String, Behavior[DumbDumbProtocol]) :: (String, Behavior[DumbDumbCatProtocol]) :: HNil
      val botsHlist: (String, Behavior[DumbDumbProtocol]) :: (String, Behavior[DumbDumbCatProtocol]) :: HNil = (DumbDumb.BOT_ID, dumbdumb) :: ("cat", dumbCat) :: HNil
      val dogshark = testKit.spawn(BotSupervisor[DumbDumbProtocol :+: DumbDumbCatProtocol :+: CNil, HBots](botsHlist, config.getConfig("bot")))
      Thread.sleep(10000000)
      dogshark ! Terminate
      Thread.sleep(1000)
    }
  }
}
