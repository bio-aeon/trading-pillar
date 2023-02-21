package su.wps.trading.pillar.storages

import su.wps.trading.pillar.models.Account

trait AccountStorage[F[_]] {
  def allAccounts: F[List[Account]]
}
