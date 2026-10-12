package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import io.github.coinductive.yfinance4s.models.PredefinedScreen as YfPredefinedScreen
import st.btu.trading.pillar.gateways.screener.{
  PredefinedScreen,
  Screener,
  ScreenerRow,
  YahooScreenerApi
}

object ScreenerFakes {

  // --- YahooScreenerApi ---

  trait FakeYahooScreenerApi extends YahooScreenerApi[IO] {
    def predefinedCalls: IO[List[(YfPredefinedScreen, Int)]]
  }

  def yahooScreenerApi(
    responses: Map[YfPredefinedScreen, IO[List[ScreenerRow]]] = Map.empty
  ): IO[FakeYahooScreenerApi] =
    Ref.of[IO, List[(YfPredefinedScreen, Int)]](List.empty).map { callsRef =>
      new FakeYahooScreenerApi {
        def predefined(screen: YfPredefinedScreen, count: Int): IO[List[ScreenerRow]] =
          callsRef
            .update(_ :+ (screen -> count)) >> responses.getOrElse(screen, IO.pure(List.empty))

        def predefinedCalls: IO[List[(YfPredefinedScreen, Int)]] = callsRef.get
      }
    }

  // --- Screener ---

  trait FakeScreener extends Screener[IO] {
    def predefinedCalls: IO[List[(PredefinedScreen, Int)]]
  }

  def screener(
    responses: Map[PredefinedScreen, IO[List[ScreenerRow]]] = Map.empty
  ): IO[FakeScreener] =
    Ref.of[IO, List[(PredefinedScreen, Int)]](List.empty).map { callsRef =>
      new FakeScreener {
        def predefined(screen: PredefinedScreen, count: Int): IO[List[ScreenerRow]] =
          callsRef
            .update(_ :+ (screen -> count)) >> responses.getOrElse(screen, IO.pure(List.empty))

        def predefinedCalls: IO[List[(PredefinedScreen, Int)]] = callsRef.get
      }
    }
}
