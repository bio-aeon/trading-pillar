package st.btu.trading.pillar.models.market

import st.btu.trading.pillar.models.domain.Currency

import java.time.LocalDate

/** One dividend payment for `ticker` from `source`, by ex-date. */
final case class DividendEvent(
  ticker: Ticker,
  exDate: LocalDate,
  amount: BigDecimal,
  currency: Option[Currency],
  source: Source
)
