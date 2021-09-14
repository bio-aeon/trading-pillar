package su.wps.trading.pillar.models.tcs

import io.circe.generic.JsonCodec
import su.wps.trading.pillar.models.tcs.Portfolio.Position

@JsonCodec
final case class Portfolio(positions: List[Position])

object Portfolio {

  @JsonCodec
  final case class Position(figi: String, ticker: String, name: String, balance: BigDecimal)
}
