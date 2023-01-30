package su.wps.trading.pillar.services

import su.wps.trading.pillar.models.domain.Command
import su.wps.trading.pillar.models.tg

trait CommandHandleService[F[_]] {
  def handleCommand(chatId: tg.ChatId, cmd: Command): F[Unit]
}
