package st.btu.trading.pillar.services

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.binance.BinanceMinute
import st.btu.trading.pillar.services.BinancePrices.*
import st.btu.trading.pillar.testkit.BinanceFakes.*

class BinancePricesSpec extends Specification {

  private val start = "2026-03-10T12:34:00Z"

  private def bar(pairName: String): BinanceMinute = minute(pairName, start)

  private def candidate(pairName: String, quote: String): Candidate =
    Candidate(bar(pairName), asset(quote))

  private val usdc = candidate("BTCUSDC", "USDC")
  private val usdt = candidate("BTCUSDT", "USDT")
  private val candidates = List(usdc, usdt)

  "BinancePrices" >> {
    "PriceBook" >> {
      "first" >> {
        "is the first candidate that has a close, with that close" >> {
          val book = PriceBook(found =
            Map(bar("BTCUSDC") -> BigDecimal(60000), bar("BTCUSDT") -> BigDecimal(59990))
          )
          book.first(candidates).value must beSome(Found(usdc, BigDecimal(60000)))
        }

        "skips a candidate known to have no bar, with nothing left to look up" >> {
          val looked = PriceBook(
            found = Map(bar("BTCUSDT") -> BigDecimal(59990)),
            absent = Set(bar("BTCUSDC"))
          ).first(candidates)
          looked.value must beSome(Found(usdt, BigDecimal(59990)))
          looked.pending must beNone
        }

        "is the candidate still to be looked up when none has a close, the first that is not known to be absent" >> {
          val looked = PriceBook(absent = Set(bar("BTCUSDC"))).first(candidates)
          looked.value must beNone
          looked.pending must beSome(bar("BTCUSDT"))
        }

        "has nothing to look up when every candidate is known to have no bar" >> {
          val looked = PriceBook(absent = Set(bar("BTCUSDC"), bar("BTCUSDT"))).first(candidates)
          looked.value must beNone
          looked.pending must beNone
        }

        "looks up an earlier candidate that is not known before it settles for a later one's close" >> {
          val looked = PriceBook(found = Map(bar("BTCUSDT") -> BigDecimal(59990))).first(candidates)
          looked.value must beSome(Found(usdt, BigDecimal(59990)))
          looked.pending must beSome(bar("BTCUSDC"))
        }

        "examines every candidate in order, whether or not one has a close" >> {
          PriceBook(found = Map(bar("BTCUSDC") -> BigDecimal(1))).first(candidates).examined must_==
            List(bar("BTCUSDC"), bar("BTCUSDT"))
        }
      }

      "withClose and withoutBar" >> {
        "make a pending candidate known" >> {
          val empty = PriceBook()
          empty.first(candidates).pending must beSome(bar("BTCUSDC"))
          empty.withoutBar(bar("BTCUSDC")).first(candidates).pending must beSome(bar("BTCUSDT"))
          empty.withClose(bar("BTCUSDC"), BigDecimal(1)).first(candidates).pending must beNone
        }
      }
    }

    "Looked" >> {
      "flatMap" >> {
        "joins what the lookups examined, and keeps the first lookup still to make" >> {
          val joined = for {
            first <- PriceBook().first(List(usdc))
            second <- PriceBook().first(List(usdt))
          } yield (first, second)
          joined.examined must_== List(bar("BTCUSDC"), bar("BTCUSDT"))
          joined.pending must beSome(bar("BTCUSDC"))
        }
      }
    }
  }
}
