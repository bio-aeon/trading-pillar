package su.wps.trading.pillar.gateways

import su.wps.trading.pillar.models.tg

trait TgGateway[F[_]] {
  def sendMessage(chatId: tg.ChatId, msg: String): F[Unit]

  def getUpdates(offset: Long): F[tg.Result[List[tg.Update]]]
}
