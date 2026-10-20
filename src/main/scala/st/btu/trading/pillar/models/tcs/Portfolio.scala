package st.btu.trading.pillar.models.tcs

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import st.btu.trading.pillar.models.tcs.Portfolio.Position

final case class Portfolio(positions: List[Position])

object Portfolio {

  final case class Position(figi: String, ticker: String, name: String, balance: BigDecimal)

  object Position {
    implicit val codecForPosition: Codec.AsObject[Position] = deriveCodec
  }

  implicit val codecForPortfolio: Codec.AsObject[Portfolio] = deriveCodec
}
