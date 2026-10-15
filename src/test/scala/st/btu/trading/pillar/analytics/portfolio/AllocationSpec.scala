package st.btu.trading.pillar.analytics.portfolio

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{Currency, Figi}

import java.time.LocalDate

class AllocationSpec extends Specification {

  private val date = LocalDate.of(2026, 9, 29)

  // Allocation.of only looks at instrumentType/currency/exchange/weight, so price/value/ticker/name
  // are irrelevant here and left at their zero value.
  private def position(
    figi: String,
    instrumentType: Option[String] = None,
    currency: Option[String] = None,
    exchange: Option[String] = None,
    weight: Option[BigDecimal] = None
  ): PositionValue =
    PositionValue(
      Figi(figi),
      ticker = None,
      name = None,
      instrumentType = instrumentType,
      currency = currency.map(Currency(_)),
      exchange = exchange,
      quantity = 1,
      price = None,
      value = None,
      weight = weight
    )

  private def snapshot(
    positions: Vector[PositionValue],
    cash: BigDecimal,
    total: BigDecimal,
    unpriced: Int
  ): PositionSnapshot = PositionSnapshot(date, Currency("RUB"), positions, cash, total, unpriced)

  "Allocation" >> {
    "of" >> {

      "groups priced positions' weight by instrument type, currency and exchange" >> {
        val positions = Vector(
          position("F1", Some("share"), Some("RUB"), Some("MOEX"), Some(BigDecimal("0.4"))),
          position("F2", Some("share"), Some("USD"), Some("NASDAQ"), Some(BigDecimal("0.3"))),
          position("F3", Some("bond"), Some("RUB"), Some("MOEX"), Some(BigDecimal("0.1")))
        )
        val allocation = Allocation.of(snapshot(positions, BigDecimal(200), BigDecimal(1000), 0))

        allocation.byInstrumentType must_== Map(
          "share" -> BigDecimal("0.7"),
          "bond" -> BigDecimal("0.1")
        )
        allocation.byCurrency must_== Map("RUB" -> BigDecimal("0.5"), "USD" -> BigDecimal("0.3"))
        allocation.byExchange must_== Map(
          "MOEX" -> BigDecimal("0.5"),
          "NASDAQ" -> BigDecimal("0.3")
        )
        allocation.cashWeight must_== BigDecimal("0.2")
        allocation.unpricedWeightUnknown must_== 0
      }

      "groups a position with no instrument metadata under 'unknown' in every breakdown" >> {
        val positions = Vector(
          position("F1", Some("share"), Some("RUB"), Some("MOEX"), Some(BigDecimal("0.6"))),
          position("UNMAPPED", weight = Some(BigDecimal("0.2")))
        )
        val allocation = Allocation.of(snapshot(positions, BigDecimal(200), BigDecimal(1000), 0))

        allocation.byInstrumentType must_== Map(
          "share" -> BigDecimal("0.6"),
          "unknown" -> BigDecimal("0.2")
        )
        allocation.byCurrency must_== Map(
          "RUB" -> BigDecimal("0.6"),
          "unknown" -> BigDecimal("0.2")
        )
        allocation.byExchange must_== Map(
          "MOEX" -> BigDecimal("0.6"),
          "unknown" -> BigDecimal("0.2")
        )
      }

      "excludes an unpriced position from every breakdown but counts it via unpricedWeightUnknown" >> {
        val positions = Vector(
          position("F1", Some("share"), Some("RUB"), Some("MOEX"), Some(BigDecimal("0.6"))),
          position("F2", Some("share"), Some("RUB"), Some("MOEX"), weight = None)
        )
        val allocation =
          Allocation.of(snapshot(positions, BigDecimal(400), BigDecimal(1000), unpriced = 1))

        allocation.byInstrumentType must_== Map("share" -> BigDecimal("0.6"))
        allocation.unpricedWeightUnknown must_== 1
      }

      "reports cashWeight 0 for a zero total instead of dividing by zero" >> {
        val allocation = Allocation.of(snapshot(Vector.empty, BigDecimal(0), BigDecimal(0), 0))
        allocation.cashWeight must_== BigDecimal(0)
      }
    }
  }
}
