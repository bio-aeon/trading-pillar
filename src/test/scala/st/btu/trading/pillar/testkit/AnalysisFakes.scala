package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.services.analysis.{AnalysisService, StockAnalysis}

object AnalysisFakes {

  trait FakeAnalysisService extends AnalysisService[IO] {
    def analyzeCalls: IO[List[Ticker]]
  }

  def analysisService(
    responses: Map[Ticker, IO[StockAnalysis]] = Map.empty
  ): IO[FakeAnalysisService] =
    Ref.of[IO, List[Ticker]](List.empty).map { callsRef =>
      new FakeAnalysisService {
        def analyze(ticker: Ticker): IO[StockAnalysis] =
          callsRef.update(_ :+ ticker) >> responses
            .getOrElse(ticker, IO.pure(StockAnalysis.empty(ticker)))

        def analyzeCalls: IO[List[Ticker]] = callsRef.get
      }
    }
}
