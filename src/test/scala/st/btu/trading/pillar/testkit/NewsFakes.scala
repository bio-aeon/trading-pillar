package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.news.{NewsItem, NewsSource, YahooNewsApi}
import st.btu.trading.pillar.models.market.Ticker

object NewsFakes {

  // --- YahooNewsApi ---

  trait FakeYahooNewsApi extends YahooNewsApi[IO] {
    def searchCalls: IO[List[(String, Int)]]
  }

  def yahooNewsApi(responses: Map[String, IO[List[NewsItem]]] = Map.empty): IO[FakeYahooNewsApi] =
    Ref.of[IO, List[(String, Int)]](List.empty).map { callsRef =>
      new FakeYahooNewsApi {
        def search(query: String, newsCount: Int): IO[List[NewsItem]] =
          callsRef
            .update(_ :+ (query -> newsCount)) >> responses.getOrElse(query, IO.pure(List.empty))

        def searchCalls: IO[List[(String, Int)]] = callsRef.get
      }
    }

  // --- NewsSource ---

  trait FakeNewsSource extends NewsSource[IO] {
    def newsCalls: IO[List[(Ticker, Int)]]
  }

  def newsSource(responses: Map[Ticker, IO[List[NewsItem]]] = Map.empty): IO[FakeNewsSource] =
    Ref.of[IO, List[(Ticker, Int)]](List.empty).map { callsRef =>
      new FakeNewsSource {
        def news(ticker: Ticker, count: Int): IO[List[NewsItem]] =
          callsRef
            .update(_ :+ (ticker -> count)) >> responses.getOrElse(ticker, IO.pure(List.empty))

        def newsCalls: IO[List[(Ticker, Int)]] = callsRef.get
      }
    }
}
