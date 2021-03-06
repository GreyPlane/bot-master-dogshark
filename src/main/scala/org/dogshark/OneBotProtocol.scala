package org.dogshark

import io.circe.Json
import io.circe.generic.JsonCodec
import org.dogshark.BotSupervisor.BotProtocol

object OneBotProtocol {
  //TODO since echo must be used to identify actor, this field should be hidden from user or be proxy
  @JsonCodec case class AnyAction(action: String, params: Option[Json] = None, echo: Option[String] = None)
  @JsonCodec case class AnyActionResponse(data: Json, echo: Option[String], retcode: Int, status: String) extends BotProtocol
  @JsonCodec case class AnyEvent(eventType: String, body: Json) extends BotProtocol

  object actionParams {
    @JsonCodec case class SendMessage(message_type: String, user_id: Option[Int], group_id: Option[Int], message: String)
  }
}
