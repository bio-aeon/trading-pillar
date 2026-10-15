package st.btu.trading.pillar.services.analysis

import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.Ticker

import java.time.ZonedDateTime

/** Everything `AnalysisService.analyze` reports for one ticker. `price`/`currency`/`asOf` come from
  * the live quote; `instrumentType`/`exchange` from instrument info; every return, SMA, RSI, ATR
  * and 52-week field is derived from stored daily bars. A field is `None` whenever its inputs
  * aren't available (no quote, no instrument info, not enough bar history, ...).
  */
final case class StockAnalysis(
  ticker: Ticker,
  price: Option[BigDecimal],
  currency: Option[Currency],
  asOf: Option[ZonedDateTime],
  instrumentType: Option[String],
  exchange: Option[String],
  return1m: Option[BigDecimal],
  return3m: Option[BigDecimal],
  return1y: Option[BigDecimal],
  sma50: Option[BigDecimal],
  sma200: Option[BigDecimal],
  aboveSma50: Option[Boolean],
  aboveSma200: Option[Boolean],
  rsi14: Option[BigDecimal],
  atr14Pct: Option[BigDecimal],
  high52w: Option[BigDecimal],
  low52w: Option[BigDecimal],
  distanceToHigh52wPct: Option[BigDecimal],
  distanceToLow52wPct: Option[BigDecimal],
  dividends12m: BigDecimal,
  dividendYieldPct: Option[BigDecimal]
)

object StockAnalysis {

  /** `ticker` with every optional field empty and `dividends12m` zero, for a ticker its market data
    * source does not recognize at all.
    */
  def empty(ticker: Ticker): StockAnalysis = StockAnalysis(
    ticker = ticker,
    price = None,
    currency = None,
    asOf = None,
    instrumentType = None,
    exchange = None,
    return1m = None,
    return3m = None,
    return1y = None,
    sma50 = None,
    sma200 = None,
    aboveSma50 = None,
    aboveSma200 = None,
    rsi14 = None,
    atr14Pct = None,
    high52w = None,
    low52w = None,
    distanceToHigh52wPct = None,
    distanceToLow52wPct = None,
    dividends12m = BigDecimal(0),
    dividendYieldPct = None
  )
}
