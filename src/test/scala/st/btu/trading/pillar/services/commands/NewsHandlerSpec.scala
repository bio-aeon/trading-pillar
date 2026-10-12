package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.news.NewsItem
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{Fakes, NewsFakes}

import java.time.{ZoneOffset, ZonedDateTime}

class NewsHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val aapl = Ticker("AAPL")
  private val publishedAt = ZonedDateTime.of(2026, 9, 30, 14, 5, 0, 0, ZoneOffset.UTC)

  private def item(
    title: String = "Apple earnings beat",
    publisher: Option[String] = Some("Reuters"),
    link: String = "https://example.com/a",
    at: Option[ZonedDateTime] = Some(publishedAt)
  ): NewsItem = NewsItem(title, publisher, link, at)

  "NewsHandler" >> {
    "handle" >> {

      "replies with usage when the ticker is missing" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          news <- NewsFakes.newsSource()
          handler = new NewsHandler[IO](tgGateway, news)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Usage")
      }

      "shows title, publisher, date and link per item" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          news <- NewsFakes.newsSource(Map(aapl -> IO.pure(List(item()))))
          handler = new NewsHandler[IO](tgGateway, news)
          _ <- handler.handle(chatId, List("aapl"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Apple earnings beat")
          msg must contain("Reuters")
          msg must contain("2026-09-30 14:05:00")
          msg must contain("https://example.com/a")
        }
      }

      "upper-cases the ticker and defaults the count to 5" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          news <- NewsFakes.newsSource(Map(aapl -> IO.pure(List(item()))))
          handler = new NewsHandler[IO](tgGateway, news)
          _ <- handler.handle(chatId, List("aapl"))
          calls <- news.newsCalls
        } yield calls must_== List((aapl, 5))
      }

      "passes a given count through to the source" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          news <- NewsFakes.newsSource(Map(aapl -> IO.pure(List(item()))))
          handler = new NewsHandler[IO](tgGateway, news)
          _ <- handler.handle(chatId, List("aapl", "3"))
          calls <- news.newsCalls
        } yield calls must_== List((aapl, 3))
      }

      "caps the count at 10" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          news <- NewsFakes.newsSource(Map(aapl -> IO.pure(List(item()))))
          handler = new NewsHandler[IO](tgGateway, news)
          _ <- handler.handle(chatId, List("aapl", "50"))
          calls <- news.newsCalls
        } yield calls must_== List((aapl, 10))
      }

      "falls back to the default count on a non-numeric argument" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          news <- NewsFakes.newsSource(Map(aapl -> IO.pure(List(item()))))
          handler = new NewsHandler[IO](tgGateway, news)
          _ <- handler.handle(chatId, List("aapl", "bogus"))
          calls <- news.newsCalls
        } yield calls must_== List((aapl, 5))
      }

      "shows ? for a missing publisher or date" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          news <- NewsFakes.newsSource(
            Map(aapl -> IO.pure(List(item(publisher = None, at = None))))
          )
          handler = new NewsHandler[IO](tgGateway, news)
          _ <- handler.handle(chatId, List("aapl"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Apple earnings beat - ? (?)")
      }

      "reports when there is no news" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          news <- NewsFakes.newsSource(Map(aapl -> IO.pure(List.empty)))
          handler = new NewsHandler[IO](tgGateway, news)
          _ <- handler.handle(chatId, List("aapl"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== "No news found."
      }
    }
  }
}
