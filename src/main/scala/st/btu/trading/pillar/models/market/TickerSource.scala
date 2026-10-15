package st.btu.trading.pillar.models.market

/** The market data source configured for `ticker`, which replaces the default for that ticker. */
final case class TickerSource(ticker: Ticker, setting: SourceSetting)
