package st.btu.trading.pillar.analytics.portfolio

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{AccountId, Currency, Figi}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.strategy.Side

import java.time.LocalDate

class RebalancerSpec extends Specification {

  private val date = LocalDate.of(2026, 9, 30)
  private val accountId = AccountId(1)

  private def position(
    figi: String,
    ticker: Option[String] = None,
    instrumentType: Option[String] = None,
    currency: Option[String] = None,
    exchange: Option[String] = None,
    weight: Option[BigDecimal] = None
  ): PositionValue =
    PositionValue(
      Figi(figi),
      ticker.map(Ticker(_)),
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
    total: BigDecimal
  ): PositionSnapshot =
    PositionSnapshot(date, Currency("RUB"), positions, cash, total, unpriced = 0)

  private def target(
    dimension: Dimension,
    key: String,
    targetPct: BigDecimal,
    driftThresholdPct: BigDecimal = BigDecimal(5),
    enabled: Boolean = true
  ): AllocationTarget =
    AllocationTarget(
      AllocationTargetId(1L),
      accountId,
      dimension,
      key,
      targetPct,
      driftThresholdPct,
      enabled
    )

  // 60% share/RUB/MOEX, 30% bond/USD/NASDAQ, 10% cash.
  private val diversified = snapshot(
    Vector(
      position(
        "F1",
        instrumentType = Some("share"),
        currency = Some("RUB"),
        exchange = Some("MOEX"),
        weight = Some(BigDecimal("0.6"))
      ),
      position(
        "F2",
        instrumentType = Some("bond"),
        currency = Some("USD"),
        exchange = Some("NASDAQ"),
        weight = Some(BigDecimal("0.3"))
      )
    ),
    cash = BigDecimal(100),
    total = BigDecimal(1000)
  )

  "Rebalancer" >> {
    "drift" >> {

      "flags a breach past the threshold" >> {
        val targets = List(
          target(Dimension.InstrumentType, "share", targetPct = BigDecimal(50)),
          target(Dimension.Currency, "USD", targetPct = BigDecimal(30)),
          target(Dimension.Exchange, "NASDAQ", targetPct = BigDecimal(40))
        )

        val result = Rebalancer.drift(Allocation.of(diversified), diversified, targets)

        result must_== List(
          Drift(
            Dimension.InstrumentType,
            "share",
            BigDecimal(50),
            BigDecimal(60),
            BigDecimal(10),
            breached = true
          ),
          Drift(
            Dimension.Currency,
            "USD",
            BigDecimal(30),
            BigDecimal(30),
            BigDecimal(0),
            breached = false
          ),
          Drift(
            Dimension.Exchange,
            "NASDAQ",
            BigDecimal(40),
            BigDecimal(30),
            BigDecimal(-10),
            breached = true
          )
        )
      }

      "reads the weight of USD for a currency target keyed usd, keeping the key as entered" >> {
        val targets = List(target(Dimension.Currency, "usd", targetPct = BigDecimal(30)))

        val result = Rebalancer.drift(Allocation.of(diversified), diversified, targets)

        result must_== List(
          Drift(
            Dimension.Currency,
            "usd",
            BigDecimal(30),
            BigDecimal(30),
            BigDecimal(0),
            breached = false
          )
        )
      }

      "ignores disabled targets" >> {
        val targets = List(
          target(Dimension.InstrumentType, "share", targetPct = BigDecimal(50)),
          target(Dimension.InstrumentType, "bond", targetPct = BigDecimal(10), enabled = false)
        )

        val result = Rebalancer.drift(Allocation.of(diversified), diversified, targets)

        result must_== List(
          Drift(
            Dimension.InstrumentType,
            "share",
            BigDecimal(50),
            BigDecimal(60),
            BigDecimal(10),
            breached = true
          )
        )
      }

      "looks up the actual weight from the snapshot's own position weights for a ticker dimension" >> {
        val snap = snapshot(
          Vector(
            position("F1", ticker = Some("AAA"), weight = Some(BigDecimal("0.25"))),
            position("F2", ticker = Some("BBB"), weight = Some(BigDecimal("0.15")))
          ),
          cash = BigDecimal(600),
          total = BigDecimal(1000)
        )
        val targets = List(
          target(
            Dimension.Ticker,
            "AAA",
            targetPct = BigDecimal(20),
            driftThresholdPct = BigDecimal(2)
          )
        )

        val result = Rebalancer.drift(Allocation.of(snap), snap, targets)

        result must_== List(
          Drift(
            Dimension.Ticker,
            "AAA",
            BigDecimal(20),
            BigDecimal(25),
            BigDecimal(5),
            breached = true
          )
        )
      }

      "treats a missing key as 0%" >> {
        val targets =
          List(
            target(
              Dimension.InstrumentType,
              "crypto",
              targetPct = BigDecimal(5),
              driftThresholdPct = BigDecimal(1)
            )
          )

        val result = Rebalancer.drift(Allocation.of(diversified), diversified, targets)

        result must_== List(
          Drift(
            Dimension.InstrumentType,
            "crypto",
            BigDecimal(5),
            BigDecimal(0),
            BigDecimal(-5),
            breached = true
          )
        )
      }
    }

    "suggest" >> {

      "without new cash, sells overweight keys down to target and buys underweight keys up to target, relative to the current total" >> {
        val targets = List(
          target(Dimension.InstrumentType, "share", targetPct = BigDecimal(50)),
          target(Dimension.Currency, "USD", targetPct = BigDecimal(30)),
          target(Dimension.Exchange, "NASDAQ", targetPct = BigDecimal(40))
        )

        val result = Rebalancer.suggest(diversified, targets, newCash = None)

        result must_== List(
          Suggestion(Dimension.InstrumentType, "share", Side.Sell, BigDecimal(100)),
          Suggestion(Dimension.Exchange, "NASDAQ", Side.Buy, BigDecimal(100))
        )
      }

      "with new cash exactly matching the total shortfall, buys each underweight key exactly its shortfall" >> {
        val snap = snapshot(
          Vector(
            position("F1", instrumentType = Some("share"), weight = Some(BigDecimal("0.8"))),
            position("F2", instrumentType = Some("bond"), weight = Some(BigDecimal("0.2")))
          ),
          cash = BigDecimal(0),
          total = BigDecimal(1000)
        )
        val targets = List(
          target(Dimension.InstrumentType, "share", targetPct = BigDecimal(50)),
          target(Dimension.InstrumentType, "bond", targetPct = BigDecimal(50))
        )

        // postTotal = 2000; targetAmount = 1000 each; actual share = 800, bond = 200
        // shortfall share = 200, bond = 800; totalShortfall = 1000 = newCash
        val result = Rebalancer.suggest(snap, targets, newCash = Some(BigDecimal(1000)))

        result must_== List(
          Suggestion(Dimension.InstrumentType, "share", Side.Buy, BigDecimal(200)),
          Suggestion(Dimension.InstrumentType, "bond", Side.Buy, BigDecimal(800))
        )
      }

      "with new cash exceeding the total shortfall, buys each underweight key exactly its shortfall and leaves the remainder as cash" >> {
        val snap = snapshot(
          Vector(
            position("F1", instrumentType = Some("share"), weight = Some(BigDecimal("0.3"))),
            position("F2", instrumentType = Some("bond"), weight = Some(BigDecimal("0.1")))
          ),
          cash = BigDecimal(600),
          total = BigDecimal(1000)
        )
        val targets = List(
          target(Dimension.InstrumentType, "share", targetPct = BigDecimal(20)),
          target(Dimension.InstrumentType, "bond", targetPct = BigDecimal(20))
        )

        // postTotal = 2000; targetAmount = 400 each; actual share = 300, bond = 100
        // shortfall share = 100, bond = 300; totalShortfall = 400, newCash = 1000 leaves 600 as cash
        val result = Rebalancer.suggest(snap, targets, newCash = Some(BigDecimal(1000)))

        result must_== List(
          Suggestion(Dimension.InstrumentType, "share", Side.Buy, BigDecimal(100)),
          Suggestion(Dimension.InstrumentType, "bond", Side.Buy, BigDecimal(300))
        )
      }

      "with new cash less than the total shortfall, distributes it across underweight keys proportionally to their shortfall against the post-deposit total" >> {
        val snap = snapshot(
          Vector(position("F1", instrumentType = Some("share"), weight = Some(BigDecimal("0.1")))),
          cash = BigDecimal(900),
          total = BigDecimal(1000)
        )
        val targets = List(
          target(Dimension.InstrumentType, "share", targetPct = BigDecimal(20)),
          target(Dimension.InstrumentType, "bond", targetPct = BigDecimal(40))
        )

        // postTotal = 1500; targetAmount share = 300, bond = 600; actual share = 100, bond = 0
        // (unheld) shortfall share = 200, bond = 600; totalShortfall = 800, newCash = 500 < 800
        val result = Rebalancer.suggest(snap, targets, newCash = Some(BigDecimal(500)))

        result must_== List(
          Suggestion(Dimension.InstrumentType, "share", Side.Buy, BigDecimal(125)),
          Suggestion(Dimension.InstrumentType, "bond", Side.Buy, BigDecimal(375))
        )
      }

      "with new cash, never sells: a key already at or above its post-deposit target gets nothing, the rest gets all the cash" >> {
        val snap = snapshot(
          Vector(
            position("F1", instrumentType = Some("share"), weight = Some(BigDecimal("0.9"))),
            position("F2", instrumentType = Some("bond"), weight = Some(BigDecimal("0.1")))
          ),
          cash = BigDecimal(0),
          total = BigDecimal(1000)
        )
        val targets = List(
          target(Dimension.InstrumentType, "share", targetPct = BigDecimal(50)),
          target(Dimension.InstrumentType, "bond", targetPct = BigDecimal(50))
        )

        val result = Rebalancer.suggest(snap, targets, newCash = Some(BigDecimal(200)))

        result must_== List(Suggestion(Dimension.InstrumentType, "bond", Side.Buy, BigDecimal(200)))
      }

      "returns no suggestions when every target already sits at its current weight" >> {
        val snap = snapshot(
          Vector(position("F1", instrumentType = Some("share"), weight = Some(BigDecimal("0.5")))),
          cash = BigDecimal(500),
          total = BigDecimal(1000)
        )
        val targets = List(target(Dimension.InstrumentType, "share", targetPct = BigDecimal(50)))

        Rebalancer.suggest(snap, targets, newCash = None) must beEmpty
      }
    }
  }
}
