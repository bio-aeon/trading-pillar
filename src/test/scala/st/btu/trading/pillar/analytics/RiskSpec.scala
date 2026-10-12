package st.btu.trading.pillar.analytics

class RiskSpec extends org.specs2.mutable.Specification {

  "Risk" >> {
    "volatility" >> {
      "is 0 when fewer than two returns" >> {
        Risk.volatility(Vector.empty) must_== BigDecimal(0)
        Risk.volatility(Vector[BigDecimal](0.01)) must_== BigDecimal(0)
      }

      "computes the annualized sample stdev of the returns" >> {
        Risk.volatility(Vector[BigDecimal](0.01, -0.01)) must_== BigDecimal("0.22449944")
      }

      "scales with sqrt(periodsPerYear)" >> {
        val returns = Vector[BigDecimal](0.01, -0.02, 0.03, -0.01)
        Risk.volatility(returns, periodsPerYear = 12) must be_<(
          Risk.volatility(returns, periodsPerYear = 252)
        )
      }
    }

    "sharpe" >> {
      "is 0 for a flat return series" >> {
        Risk.sharpe(Vector[BigDecimal](0, 0, 0)) must_== BigDecimal(0)
      }

      "is 0 when fewer than two returns" >> {
        Risk.sharpe(Vector[BigDecimal](0.01)) must_== BigDecimal(0)
      }

      "is lower with a higher risk-free rate" >> {
        val returns = Vector[BigDecimal](0.01, -0.02, 0.03, -0.01)
        Risk.sharpe(returns, riskFreePerPeriod = BigDecimal(0.02)) must be_<(Risk.sharpe(returns))
      }
    }

    "sortino" >> {
      "is 0 when there is no negative excess return" >> {
        Risk.sortino(Vector[BigDecimal](0.01, 0.02, 0.03)) must_== BigDecimal(0)
      }

      "is 0 when fewer than two returns" >> {
        Risk.sortino(Vector[BigDecimal](0.01)) must_== BigDecimal(0)
      }

      "computes mean(excess) / downside-deviation(excess) annualized" >> {
        Risk.sortino(Vector[BigDecimal](0.01, -0.02, 0.03, -0.01)) must_== BigDecimal("2.50998008")
      }
    }

    "maxDrawdown" >> {
      "is 0 for an empty series" >> {
        Risk.maxDrawdown(Vector.empty) must_== BigDecimal(0)
      }

      "finds the largest peak-to-trough fraction on a constructed path" >> {
        // peak 100 -> trough 80 (20%); new peak 120 -> trough 60 (50%, the largest); recovers to 90
        Risk.maxDrawdown(Vector[BigDecimal](100, 80, 120, 60, 90)) must_== BigDecimal(0.5)
      }
    }

    "valueAtRisk" >> {
      "is 0 for an empty series" >> {
        Risk.valueAtRisk(Vector.empty, BigDecimal(0.95)) must_== BigDecimal(0)
      }

      "negates the empirical (1 - confidence) quantile of a small return sample" >> {
        // sorted: -0.05, -0.03, -0.01, 0.02, 0.04; n = 5, p = 1 - 0.95 = 0.05
        // index = ceil(0.05 * 5) - 1 = ceil(0.25) - 1 = 1 - 1 = 0 -> -(-0.05) = 0.05
        val returns = Vector[BigDecimal](0.02, -0.05, 0.04, -0.01, -0.03)
        Risk.valueAtRisk(returns, BigDecimal(0.95)) must_== BigDecimal(0.05)
      }

      "uses the nearest-rank index exactly at an integer p * n, not one rank further" >> {
        // sorted: -0.08, -0.04, 0.01, 0.05; n = 4, p = 1 - 0.75 = 0.25, p * n = 1.0 exactly
        // index = ceil(1.0) - 1 = 1 - 1 = 0 -> -(-0.08) = 0.08
        val returns = Vector[BigDecimal](-0.04, 0.05, -0.08, 0.01)
        Risk.valueAtRisk(returns, BigDecimal(0.75)) must_== BigDecimal(0.08)
      }

      "clamps the index to the series' lower bound when confidence is 100%" >> {
        // p = 1 - 1 = 0 for any n, so ceil(0 * n) - 1 = -1 before clamping to 0 -> worst return
        val returns = Vector[BigDecimal](0.02, -0.05, 0.04, -0.01, -0.03)
        Risk.valueAtRisk(returns, BigDecimal(1)) must_== BigDecimal(0.05)
      }
    }

    "beta" >> {
      "is 1 for a series against itself" >> {
        val returns = Vector[BigDecimal](0.01, -0.02, 0.03, 0.015, -0.01)
        Risk.beta(returns, returns) must beSome(BigDecimal(1))
      }

      "scales linearly: 2x the benchmark has beta 2" >> {
        val benchmark = Vector[BigDecimal](0.01, -0.02, 0.03, 0.02)
        val returns = benchmark.map(_ * 2)
        Risk.beta(returns, benchmark) must beSome(BigDecimal(2))
      }

      "is None when fewer than two aligned pairs are available" >> {
        Risk.beta(Vector[BigDecimal](0.01), Vector[BigDecimal](0.02)) must beNone
      }

      "is None when the benchmark has zero variance" >> {
        val returns = Vector[BigDecimal](0.01, -0.02, 0.03)
        val flatBenchmark = Vector[BigDecimal](0.01, 0.01, 0.01)
        Risk.beta(returns, flatBenchmark) must beNone
      }
    }
  }
}
