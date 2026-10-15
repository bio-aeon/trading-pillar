package st.btu.trading.pillar.services.commands

import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.services.analysis.StockAnalysis

import java.time.ZonedDateTime

// Pure reply formatting shared by AnalyzeHandler (one StockAnalysis) and CompareHandler (two,
// side by side). `rows` is the single source of truth for both: one label plus one renderer per
// field, in reply order, so the two commands can never drift out of sync on which fields they
// print or how. A renderer never raises and never sees the ticker-level `NotAvailable` outside its
// own field - every Option field is rendered independently as `NotAvailable` when empty.
object AnalysisFormat {

  private val NotAvailable = "n/a"

  private val rows: List[(String, StockAnalysis => String)] = List(
    "Price" -> (a => money(a.price, a.currency)),
    "As of" -> (a => a.asOf.fold(NotAvailable)(formatInstant)),
    "Type" -> (a => a.instrumentType.getOrElse(NotAvailable)),
    "Exchange" -> (a => a.exchange.getOrElse(NotAvailable)),
    "1m return" -> (a => pct(a.return1m)),
    "3m return" -> (a => pct(a.return3m)),
    "1y return" -> (a => pct(a.return1y)),
    "SMA50" -> (a => dec(a.sma50)),
    "Above SMA50" -> (a => bool(a.aboveSma50)),
    "SMA200" -> (a => dec(a.sma200)),
    "Above SMA200" -> (a => bool(a.aboveSma200)),
    "RSI14" -> (a => dec(a.rsi14)),
    "ATR14" -> (a => pct(a.atr14Pct)),
    "52w high" -> (a => dec(a.high52w)),
    "Dist. to 52w high" -> (a => pct(a.distanceToHigh52wPct)),
    "52w low" -> (a => dec(a.low52w)),
    "Dist. to 52w low" -> (a => pct(a.distanceToLow52wPct)),
    "Dividends (12m)" -> (a => Replies.scaled(a.dividends12m)),
    "Dividend yield" -> (a => pct(a.dividendYieldPct))
  )

  def single(analysis: StockAnalysis): String =
    (analysis.ticker.value :: rows.map { case (label, render) =>
      s"$label: ${render(analysis)}"
    }).mkString("\n")

  def sideBySide(a: StockAnalysis, b: StockAnalysis): String = {
    val header = s"${a.ticker.value} vs ${b.ticker.value}"
    val lines = rows.map { case (label, render) =>
      s"$label: ${render(a)} | ${render(b)}"
    }
    (header :: lines).mkString("\n")
  }

  private def money(price: Option[BigDecimal], currency: Option[Currency]): String =
    price.fold(NotAvailable)(p =>
      currency.fold(Replies.scaled(p))(c => s"${Replies.scaled(p)} ${c.value}")
    )

  private def dec(v: Option[BigDecimal]): String = v.fold(NotAvailable)(Replies.scaled)

  // v is already a percentage figure (AnalysisServiceImpl.pctOf multiplies by 100), unlike
  // Replies.pct's 0-1 fraction input - see DriftHandler's own pct for the same distinction.
  private def pct(v: Option[BigDecimal]): String =
    v.fold(NotAvailable)(x => s"${Replies.scaled(x)}%")

  private def bool(v: Option[Boolean]): String = v.fold(NotAvailable)(b => if (b) "yes" else "no")

  private def formatInstant(z: ZonedDateTime): String = z.format(Replies.TimeFormatter)
}
