package st.btu.trading.pillar.models.binance

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.binance.BinanceTradeId.value
import st.btu.trading.pillar.models.domain.{AccountId, OperationRef}
import st.btu.trading.pillar.testkit.BinanceFakes.*

import java.time.Instant

class BinanceRecordSpec extends Specification {

  private val accountId = AccountId(1)

  private def recordOf(source: BinanceSource, id: String): BinanceRecord =
    BinanceRecord(accountId, source, BinanceRecordId(id), Instant.EPOCH, obj("{}"))

  "BinanceRecord" >> {
    "of" >> {
      "a trade is named by its symbol and its trade id, and dated by its time" >> {
        val attributes = trade("BTCUSDC", 28457, "2026-03-10T12:34:56.789Z", "4", "12", "48", true)
        BinanceRecord
          .of(accountId, BinanceSource.Trade, attributes)
          .map(r => (r.recordId, r.occurredAt)) must beRight(
          (BinanceRecordId("BTCUSDC/28457"), at("2026-03-10T12:34:56.789Z"))
        )
      }

      "a deposit is named by its id and dated by its insert time" >> {
        val attributes = deposit("9876", "BTC", "0.5", "2026-03-10T08:00:00Z")
        BinanceRecord
          .of(accountId, BinanceSource.Deposit, attributes)
          .map(r => (r.recordId, r.occurredAt)) must beRight(
          (BinanceRecordId("9876"), at("2026-03-10T08:00:00Z"))
        )
      }

      "a withdrawal is dated by its apply time, a UTC date and time with no zone" >> {
        val attributes = withdrawal("5544", "BTC", "0.5", "0.0005", "2019-10-12T11:12:02Z")
        BinanceRecord
          .of(accountId, BinanceSource.Withdrawal, attributes)
          .map(r => (r.recordId, r.occurredAt)) must beRight(
          (BinanceRecordId("5544"), at("2019-10-12T11:12:02Z"))
        )
      }

      "a fiat order and a fiat payment are named by their order number and dated by their creation" >> {
        val order = fiatOrder("O1", "EUR", "100", "99", "1", "2026-03-10T09:00:00Z")
        val payment =
          fiatPayment("P1", "EUR", "BTC", "100", "0.001", "2", "98000", "2026-03-10T10:00:00Z")
        BinanceRecord
          .of(accountId, BinanceSource.FiatDeposit, order)
          .map(r => (r.recordId, r.occurredAt)) must beRight(
          (BinanceRecordId("O1"), at("2026-03-10T09:00:00Z"))
        )
        BinanceRecord
          .of(accountId, BinanceSource.FiatBuy, payment)
          .map(r => (r.recordId, r.occurredAt)) must beRight(
          (BinanceRecordId("P1"), at("2026-03-10T10:00:00Z"))
        )
      }

      "a Convert is named by the digits of its order id, which a double would round" >> {
        val attributes =
          convert("940708407462087201", "USDT", "20", "BNB", "0.06154036", "2026-03-10T11:00:00Z")
        BinanceRecord
          .of(accountId, BinanceSource.Convert, attributes)
          .map(_.recordId) must beRight(BinanceRecordId("940708407462087201"))
      }

      "a distribution is named by its id, as the digits Binance sent, and dated by its distribution time" >> {
        val attributes = distribution(1637366104L, "BHFT", "10", "2026-03-10T08:30:00Z")
        BinanceRecord
          .of(accountId, BinanceSource.Dividend, attributes)
          .map(r => (r.recordId, r.occurredAt)) must beRight(
          (BinanceRecordId("1637366104"), at("2026-03-10T08:30:00Z"))
        )
      }

      "a distribution id that a double would round keeps its digits" >> {
        val attributes = obj(
          """{"id":940708407462087201,"asset":"BHFT","amount":"1","divTime":1773146096789,"direction":1}"""
        )
        BinanceRecord
          .of(accountId, BinanceSource.Dividend, attributes)
          .map(_.recordId) must beRight(BinanceRecordId("940708407462087201"))
      }

      "a dust conversion is named by its own transaction id, not that of its details, and dated by its operation time" >> {
        val attributes = dustConversion(
          45178372831L,
          "2026-03-10T09:15:00Z",
          dustDetail(4359321L, "USDT", "0.0009", "0.000441", "0.000009", "2026-03-10T09:15:00Z")
        )
        BinanceRecord
          .of(accountId, BinanceSource.Dust, attributes)
          .map(r => (r.recordId, r.occurredAt)) must beRight(
          (BinanceRecordId("45178372831"), at("2026-03-10T09:15:00Z"))
        )
      }

      "refuses a distribution without its time, naming the record and the field" >> {
        val attributes = obj("""{"id":5,"asset":"BHFT"}""")
        BinanceRecord
          .of(accountId, BinanceSource.Dividend, attributes) must beLeft[BinanceRecordError](
          BinanceRecordError
            .MissingField(BinanceSource.Dividend, Some(BinanceRecordId("5")), "divTime")
        )
      }

      "refuses a dust conversion without its transaction id, naming the field" >> {
        val attributes = obj("""{"operateTime":1773146096789}""")
        BinanceRecord
          .of(accountId, BinanceSource.Dust, attributes) must beLeft[BinanceRecordError](
          BinanceRecordError.MissingField(BinanceSource.Dust, None, "transId")
        )
      }

      "refuses a record without its id, naming the field" >> {
        val attributes = obj(s"""{"coin":"BTC","insertTime":1}""")
        BinanceRecord
          .of(accountId, BinanceSource.Deposit, attributes) must beLeft[BinanceRecordError](
          BinanceRecordError.MissingField(BinanceSource.Deposit, None, "id")
        )
      }

      "refuses a record without its time, naming the record and the field" >> {
        val attributes = obj("""{"id":"9876","coin":"BTC"}""")
        BinanceRecord
          .of(accountId, BinanceSource.Deposit, attributes) must beLeft[BinanceRecordError](
          BinanceRecordError
            .MissingField(BinanceSource.Deposit, Some(BinanceRecordId("9876")), "insertTime")
        )
      }

      "refuses a time that is not of the form Binance uses" >> {
        val attributes = obj("""{"id":"5544","applyTime":"yesterday"}""")
        BinanceRecord.of(accountId, BinanceSource.Withdrawal, attributes) must
          beLeft[BinanceRecordError](
            BinanceRecordError.InvalidField(
              BinanceSource.Withdrawal,
              Some(BinanceRecordId("5544")),
              "applyTime",
              "yesterday"
            )
          )
      }
    }

    "ref" >> {
      "is the source and the record id" >> {
        recordOf(BinanceSource.Trade, "BTCUSDC/28457").ref must_==
          OperationRef("trade/BTCUSDC/28457")
        recordOf(BinanceSource.FiatBuy, "P1").ref must_== OperationRef("fiat_buy/P1")
        recordOf(BinanceSource.Dividend, "1637366104").ref must_==
          OperationRef("dividend/1637366104")
        recordOf(BinanceSource.Dust, "45178372831").ref must_== OperationRef("dust/45178372831")
      }
    }

    "refWith" >> {
      "adds the suffix after a slash" >> {
        recordOf(BinanceSource.Trade, "BTCUSDC/28457").refWith("fee") must_==
          OperationRef("trade/BTCUSDC/28457/fee")
      }
    }

    "owns" >> {
      val owner = recordOf(BinanceSource.Trade, "BTCUSDC/28")

      "the record's own ref" >> {
        owner.owns(OperationRef("trade/BTCUSDC/28")) must beTrue
      }

      "a ref under the record's own" >> {
        owner.owns(OperationRef("trade/BTCUSDC/28/fee")) must beTrue
        owner.owns(OperationRef("trade/BTCUSDC/28/received")) must beTrue
      }

      "the refs of the exchanges of the details of a dust conversion" >> {
        val conversion = recordOf(BinanceSource.Dust, "45178372831")
        conversion.owns(OperationRef("dust/45178372831/4359321")) must beTrue
        conversion.owns(OperationRef("dust/45178372831/4359321/received")) must beTrue
        conversion.owns(OperationRef("dust/451783728310/4359321")) must beFalse
      }

      "not the ref of a record whose id only starts with the same digits" >> {
        owner.owns(OperationRef("trade/BTCUSDC/281")) must beFalse
        owner.owns(OperationRef("trade/BTCUSDC/281/fee")) must beFalse
      }

      "not the ref of a record of another source" >> {
        owner.owns(OperationRef("deposit/BTCUSDC/28")) must beFalse
      }
    }

    "tradeSymbol" >> {
      "is the pair of a trade" >> {
        val attributes = trade("BTCUSDC", 1, "2026-03-10T12:34:56Z", "4", "12", "48", false)
        record(accountId, BinanceSource.Trade, attributes).tradeSymbol must beSome(
          BinanceSymbol("BTCUSDC")
        )
      }

      "is none for a record of another source" >> {
        val attributes = deposit("9876", "BTC", "0.5", "2026-03-10T08:00:00Z")
        record(accountId, BinanceSource.Deposit, attributes).tradeSymbol must beNone
      }
    }

    "tradeIdOf" >> {
      "is the id Binance gave the trade" >> {
        val attributes = trade("BTCUSDC", 28457, "2026-03-10T12:34:56Z", "4", "12", "48", false)
        BinanceRecord.tradeIdOf(attributes).map(_.value) must beSome(28457L)
      }
    }
  }
}
