package su.wps.trading.pillar.models.domain

import su.wps.trading.pillar.models.domain.Portfolio.Position

final case class Portfolio(positions: List[Position])

object Portfolio {

  final case class Position(figi: String, ticker: String, name: String, balance: BigDecimal)
}
