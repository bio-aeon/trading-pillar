package st.btu.trading.pillar.strategy.backtest

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.{Bar, Ticker}
import st.btu.trading.pillar.strategy.reference.MovingAverageCrossoverStrategy
import st.btu.trading.pillar.strategy.*
import st.btu.trading.pillar.testkit.StrategyFakes
import st.btu.trading.pillar.testkit.StrategyFakes.ScriptedStrategy

import java.time.LocalDate

class BacktestEngineSpec extends Specification {

  private val ticker = Ticker("AAPL")

  private val d1 = LocalDate.of(2024, 1, 1)
  private val d2 = LocalDate.of(2024, 1, 2)
  private val d3 = LocalDate.of(2024, 1, 3)
  private val d4 = LocalDate.of(2024, 1, 4)

  private def flat(date: LocalDate, price: BigDecimal): Bar =
    StrategyFakes.flatBar(ticker, date, price)

  private def buyOn(
    date: LocalDate,
    reason: String = "buy"
  ): (StrategyContext, Bar) => List[StrategySignal] =
    (ctx, bar) =>
      if (bar.date == date) List(StrategySignal.Buy(ctx.ticker, Sizing.AllIn, reason))
      else List(StrategySignal.Hold("no"))

  private def config(
    cash: BigDecimal,
    commission: BigDecimal = 0,
    slippageBps: Int = 0
  ): BacktestConfig =
    BacktestConfig(cash, commission, slippageBps)

  "BacktestEngine" >> {
    "run" >> {

      "fills a buy at the next bar's open (with slippage), not the signal bar's close" >> {
        val bars = List(
          flat(d1, 100),
          StrategyFakes.ohlcBar(ticker, d2, open = 100, high = 111, low = 99, close = 110),
          StrategyFakes.ohlcBar(ticker, d3, open = 120, high = 121, low = 119, close = 115)
        )
        val strategy = ScriptedStrategy(StrategyId("buy-on-d2"), warmupBars = 1, buyOn(d2))

        val result = BacktestEngine.run(strategy, bars, config(cash = 10000, slippageBps = 50))

        result must beRight[BacktestResult].like { case r =>
          r.trades must haveSize(1)
          val trade = r.trades.head
          trade.date must_== d3
          trade.side must_== Side.Buy
          // 120 * (1 + 50/10000) = 120.6, not bar2's close (110) or bar2's open (100)
          trade.price must_== BigDecimal("120.6")
        }
      }

      "accounts for commission and slippage: cash after a buy equals initial cash minus quantity times fill price minus commission" >> {
        val bars = List(
          flat(d1, 100),
          StrategyFakes.ohlcBar(ticker, d2, open = 100, high = 100, low = 100, close = 100),
          StrategyFakes.ohlcBar(ticker, d3, open = 100, high = 100, low = 100, close = 100)
        )
        val strategy = ScriptedStrategy(StrategyId("buy-on-d2"), warmupBars = 1, buyOn(d2))

        val result =
          BacktestEngine.run(strategy, bars, config(cash = 10000, commission = BigDecimal("0.001")))

        result must beRight[BacktestResult].like { case r =>
          r.trades must haveSize(1)
          val trade = r.trades.head
          // fillPrice = 100 (no slippage); maxQty = floor(10000 / (100 * 1.001)) = 99
          trade.quantity must_== 99L
          trade.commission must_== BigDecimal("9.9")
          val fillPoint = r.equityCurve.find(_.date == d3)
          fillPoint must beSome[EquityPoint].like { case p => p.cash must_== BigDecimal("90.1") }
        }
      }

      "produces identical results for identical inputs (determinism)" >> {
        val bars = trendingBars
        val strategy = new MovingAverageCrossoverStrategy(2, 3)
        val cfg = config(cash = 10000, commission = BigDecimal("0.001"), slippageBps = 10)

        val r1 = BacktestEngine.run(strategy, bars, cfg)
        val r2 = BacktestEngine.run(strategy, bars, cfg)

        r1 must_== r2
        r1 must beRight
      }

      "fails when there are no bars" >> {
        val strategy = ScriptedStrategy(StrategyId("s"), warmupBars = 1, buyOn(d1))
        BacktestEngine.run(strategy, Nil, config(cash = 1000)) must beLeft[BacktestError](
          BacktestError.NoBars
        )
      }

      "fails when fewer bars than the strategy's warmup requires" >> {
        val strategy = ScriptedStrategy(StrategyId("s"), warmupBars = 5, buyOn(d1))
        val bars = List(flat(d1, 100), flat(d2, 101))
        BacktestEngine.run(strategy, bars, config(cash = 1000)) must
          beLeft[BacktestError](BacktestError.NotEnoughBars(5, 2))
      }

      "fails when bars are not strictly chronological" >> {
        val strategy = ScriptedStrategy(StrategyId("s"), warmupBars = 1, buyOn(d1))
        val bars = List(flat(d2, 100), flat(d1, 101))
        BacktestEngine.run(strategy, bars, config(cash = 1000)) must beLeft.like {
          case _: BacktestError.InvalidBars => ok
          case other => ko(s"expected InvalidBars, got $other")
        }
      }

      "fails when bar dates repeat" >> {
        val strategy = ScriptedStrategy(StrategyId("s"), warmupBars = 1, buyOn(d1))
        val bars = List(flat(d1, 100), flat(d1, 101))
        BacktestEngine.run(strategy, bars, config(cash = 1000)) must beLeft.like {
          case _: BacktestError.InvalidBars => ok
          case other => ko(s"expected InvalidBars, got $other")
        }
      }

      "ignores, and records as skipped, a sell signal when there is no position" >> {
        val strategy = ScriptedStrategy(
          StrategyId("always-sell"),
          warmupBars = 1,
          (ctx, _) => List(StrategySignal.Sell(ctx.ticker, Sizing.ClosePosition, "sell"))
        )
        val bars = List(flat(d1, 100), flat(d2, 100), flat(d3, 100))

        val result = BacktestEngine.run(strategy, bars, config(cash = 10000))

        result must beRight[BacktestResult].like { case r =>
          r.trades must beEmpty
          // bar1's signal is ignored at bar2's fill, bar2's signal is ignored at bar3's fill, and
          // bar3's own (last-bar) signal never gets a chance to fill at all.
          r.skipped must haveSize(3)
          r.skipped.count(_.reason.contains("Sell without a position")) must_== 2
          r.skipped.count(_.reason.contains("Unfilled")) must_== 1
        }
      }

      "ignores, and records as skipped, a buy signal that resolves to zero quantity" >> {
        val strategy = ScriptedStrategy(StrategyId("buy-on-d1"), warmupBars = 1, buyOn(d1))
        val bars = List(flat(d1, 100), flat(d2, 100), flat(d3, 100))

        // initialCash = 1 cannot afford even a single share at price 100
        val result = BacktestEngine.run(strategy, bars, config(cash = 1))

        result must beRight[BacktestResult].like { case r =>
          r.trades must beEmpty
          r.skipped must haveSize(1)
          r.skipped.head.reason must contain("zero quantity")
        }
      }

      "does not fill a signal produced on the last bar, and reports it as skipped and unfilled" >> {
        val strategy = ScriptedStrategy(
          StrategyId("always-buy"),
          warmupBars = 1,
          (ctx, _) => List(StrategySignal.Buy(ctx.ticker, Sizing.AllIn, "buy"))
        )
        val bars = List(flat(d1, 100), flat(d2, 100))

        val result = BacktestEngine.run(strategy, bars, config(cash = 10000))

        result must beRight[BacktestResult].like { case r =>
          // The signal from bar1 fills at bar2's open even though bar2 is the last bar.
          r.trades must haveSize(1)
          // The new signal produced at bar2 (the last bar) cannot fill.
          r.skipped must haveSize(1)
          r.skipped.head.reason must contain("Unfilled")
        }
      }

      "adds repeated buys into a single accumulating position" >> {
        val strategy = ScriptedStrategy(
          StrategyId("buy-twice"),
          warmupBars = 1,
          (ctx, _) =>
            if (ctx.position.exists(_.quantity >= 10)) List(StrategySignal.Hold("done"))
            else List(StrategySignal.Buy(ctx.ticker, Sizing.Quantity(5), "buy"))
        )
        val bars = List(
          flat(d1, 100),
          StrategyFakes.ohlcBar(ticker, d2, open = 100, high = 100, low = 100, close = 100),
          StrategyFakes.ohlcBar(ticker, d3, open = 200, high = 200, low = 200, close = 200),
          StrategyFakes.ohlcBar(ticker, d4, open = 200, high = 200, low = 200, close = 200)
        )

        val result = BacktestEngine.run(strategy, bars, config(cash = 100000))

        result must beRight[BacktestResult].like { case r =>
          // 5 @ 100 (bar2) then 5 @ 200 (bar3), blended into one 10-share position.
          r.trades must haveSize(2)
          r.trades.map(_.quantity) must_== List(5L, 5L)
          val last = r.equityCurve.last
          last.quantity must_== 10L
          last.cash must_== BigDecimal(100000 - 5 * 100 - 5 * 200)
        }
      }
    }

    "runPortfolio" >> {

      "produces the same trades and equity as run for a single ticker" >> {
        val bars = trendingBars
        val strategy = new MovingAverageCrossoverStrategy(2, 3)
        val cfg = config(cash = 10000, commission = BigDecimal("0.001"), slippageBps = 10)

        val single = BacktestEngine.run(strategy, bars, cfg)
        val portfolio = BacktestEngine.runPortfolio(strategy, Map(ticker -> bars), cfg)

        single must beRight[BacktestResult].like { case s =>
          portfolio must beRight[PortfolioBacktestResult].like { case p =>
            p.tradesByTicker.get(ticker) must beSome(s.trades)
            p.equityCurve must_== s.equityCurve
            p.metrics must_== s.metrics
            p.dateFrom must_== s.dateFrom
            p.dateTo must_== s.dateTo
          }
        }
      }

      "shares one cash balance across tickers: an all-in buy on the alphabetically first ticker " +
        "leaves nothing for the second" >> {
          val tickerA = Ticker("AAA")
          val tickerB = Ticker("BBB")
          def flatFor(t: Ticker): List[Bar] =
            List(
              StrategyFakes.flatBar(t, d1, 100),
              StrategyFakes.flatBar(t, d2, 100),
              StrategyFakes
                .flatBar(t, d3, 100)
            )
          // Buys all-in the first time it sees a ticker flat (no position); otherwise holds - so
          // the alphabetically-first ticker (A) takes the shared cash on d2, and B's identical
          // all-in attempt (d2, then again d3) has none left.
          val strategy = ScriptedStrategy(
            StrategyId("buy-once-per-ticker"),
            warmupBars = 1,
            (ctx, _) =>
              if (ctx.position.isEmpty) List(StrategySignal.Buy(ctx.ticker, Sizing.AllIn, "buy"))
              else List(StrategySignal.Hold("done"))
          )

          val result = BacktestEngine.runPortfolio(
            strategy,
            Map(tickerA -> flatFor(tickerA), tickerB -> flatFor(tickerB)),
            config(cash = 10000)
          )

          result must beRight[PortfolioBacktestResult].like { case r =>
            r.tickers must_== List(tickerA, tickerB)
            r.tradesByTicker.getOrElse(tickerA, Nil) must haveSize(1)
            r.tradesByTicker(tickerA).head.quantity must_== 100L
            r.tradesByTicker.getOrElse(tickerB, Nil) must beEmpty
            r.skipped.count(_.reason.contains("Buy resolved to zero quantity")) must_== 2
            r.skipped.count(_.reason.contains("Unfilled at end of backtest")) must_== 1
            // cash is fully spent by A on d2, so the portfolio equity curve never reflects a
            // second all-in purchase - total equity stays at the initial cash throughout (flat
            // prices, no commission/slippage in this config).
            r.equityCurve.map(_.equity).toSet must_== Set(BigDecimal(10000))
          }
        }
    }
  }

  // A downtrend then a sharp uptrend, producing exactly one fast(2)/slow(3) SMA crossover.
  private def trendingBars: List[Bar] = {
    val closes = List(
      BigDecimal(10),
      BigDecimal(9),
      BigDecimal(8),
      BigDecimal(7),
      BigDecimal(6),
      BigDecimal(8),
      BigDecimal(11),
      BigDecimal(14)
    )
    closes.zipWithIndex.map { case (c, i) => flat(LocalDate.of(2024, 1, i + 1), c) }
  }
}
