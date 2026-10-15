package st.btu.trading.pillar.gateways

import st.btu.trading.pillar.models.market.ClassCode

/** A Tinkoff market data source that can serve a ticker from a chosen trading board. */
trait TinkoffBoards[F[_]] {

  /** A source that looks tickers up on the board with `classCode`, or on the default board when
    * `None`. Views of one source share what it has already resolved.
    */
  def onBoard(classCode: Option[ClassCode]): MarketDataSource[F]
}
