package st.btu.trading.pillar.models.binance

import st.btu.trading.pillar.models.domain.AccountOperation

/** A Binance record paired with its translations into `AccountOperation`s. The two are recorded
  * together.
  */
final case class BinanceTranslatedRecord(
  record: BinanceRecord,
  translations: List[AccountOperation]
)
