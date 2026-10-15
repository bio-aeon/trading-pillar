package st.btu.trading.pillar.models.market

/** A ticker together with the source its stored data is read from. */
final case class SourcedTicker(ticker: Ticker, source: Source)
