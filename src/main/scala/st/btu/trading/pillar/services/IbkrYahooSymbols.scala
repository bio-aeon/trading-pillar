package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.market.{ClassCode, Ticker, YahooSymbol}

// The symbol Yahoo Finance knows an instrument listed at Interactive Brokers by, from IB's symbol
// and the exchange IB lists it on. Only the exchanges listed here have a rule.
private[services] object IbkrYahooSymbols {

  private val NoSuffix = ""

  // Yahoo's suffix by IB's listing exchange. IBIS and IBIS2 are Xetra, SBF is Euronext Paris, AEB is
  // Euronext Amsterdam, EBS is the SIX Swiss Exchange and TSE is Toronto.
  private val Suffixes: Map[String, String] = Map(
    "NYSE" -> NoSuffix,
    "NASDAQ" -> NoSuffix,
    "ARCA" -> NoSuffix,
    "AMEX" -> NoSuffix,
    "BATS" -> NoSuffix,
    "IEX" -> NoSuffix,
    "LSE" -> ".L",
    "IBIS" -> ".DE",
    "IBIS2" -> ".DE",
    "SBF" -> ".PA",
    "AEB" -> ".AS",
    "EBS" -> ".SW",
    "TSE" -> ".TO"
  )

  // A space or a dot that sets a share class apart from the rest of a symbol, as in BRK B or BT.A.
  // Yahoo writes a hyphen there.
  private val ShareClassSeparator = "[ .](?=.)".r

  // A dot at the end of an IB symbol, as in the London symbol BP., sets no share class apart.
  private val TrailingDot = "."

  /** The Yahoo symbol for the instrument IB calls `symbol` and lists on `exchange`, or `None` when
    * the exchange has no rule.
    *
    * The symbol takes the suffix of the exchange, none for a US exchange. A space or a dot before a
    * share class becomes a hyphen, and a dot at the end of the symbol is dropped, since the suffix
    * brings its own.
    */
  def of(symbol: Ticker, exchange: ClassCode): Option[YahooSymbol] =
    Suffixes.get(exchange.value).map { suffix =>
      val classed = ShareClassSeparator.replaceAllIn(symbol.value, "-")
      YahooSymbol(classed.stripSuffix(TrailingDot) + suffix)
    }
}
