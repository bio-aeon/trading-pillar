package st.btu.trading.pillar.gateways.tcs

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import io.grpc.Status
import org.specs2.mutable.Specification
import ru.tinkoff.piapi.contract.v1.instruments.{Instrument, InstrumentIdType, InstrumentResponse}
import st.btu.trading.pillar.models.domain.{Broker, BrokerInstrumentId, Currency, InstrumentKey}
import st.btu.trading.pillar.models.market.{BrokerInstrument, ClassCode, MarketDataError, Ticker}
import st.btu.trading.pillar.testkit.{MarketDataFakes, TcsMarketDataFakes}

private final case class BuiltTcsInstrumentDirectory(
  directory: TcsInstrumentDirectory[IO],
  instruments: TcsMarketDataFakes.FakeInstrumentsService,
  storage: MarketDataFakes.FakeBrokerInstrumentStorage
)

class TcsInstrumentDirectorySpec extends Specification with CatsEffect {

  private val figi = "BBG004730N88"
  private val key = InstrumentKey(Broker.Tinkoff, BrokerInstrumentId(figi))

  private val response = InstrumentResponse(instrument =
    Some(
      Instrument(
        figi = figi,
        ticker = "SBER",
        classCode = "TQBR",
        currency = "rub",
        name = "Sberbank",
        exchange = "MOEX",
        instrumentType = "share"
      )
    )
  )

  private def directoryOver(
    byFigi: Map[String, IO[InstrumentResponse]],
    stored: List[BrokerInstrument] = Nil
  ): IO[BuiltTcsInstrumentDirectory] =
    for {
      instruments <- TcsMarketDataFakes.instrumentsService(byFigi = byFigi)
      storage <- MarketDataFakes.brokerInstrumentStorage(stored)
    } yield BuiltTcsInstrumentDirectory(
      TcsInstrumentDirectory.create[IO](instruments, storage),
      instruments,
      storage
    )

  "TcsInstrumentDirectory" >> {
    "byKey" >> {

      "answers nothing for a key of another broker, without calling Tinkoff" >> {
        val otherBroker = InstrumentKey(Broker.Ibkr, BrokerInstrumentId(figi))
        for {
          built <- directoryOver(Map(figi -> IO.pure(response)))
          found <- built.directory.byKey(otherBroker)
          calls <- built.instruments.getInstrumentByCalls
        } yield {
          found must beNone
          calls must beEmpty
        }
      }

      "answers from the storage without calling the broker" >> {
        val known = MarketDataFakes.brokerInstrument(figi, Ticker("SBER"))
        for {
          built <- directoryOver(Map.empty, stored = List(known))
          found <- built.directory.byKey(key)
          calls <- built.instruments.getInstrumentByCalls
        } yield {
          found must beSome(known)
          calls must beEmpty
        }
      }

      "fetches an instrument the storage lacks, maps the broker's record and stores it" >> {
        for {
          built <- directoryOver(Map(figi -> IO.pure(response)))
          before <- IO.realTimeInstant
          found <- built.directory.byKey(key)
          after <- IO.realTimeInstant
          stored <- built.storage.all
        } yield {
          found must beSome[BrokerInstrument].like { case instrument =>
            instrument.instrument must_== key
            instrument.ticker must_== Ticker("SBER")
            instrument.classCode must beSome(ClassCode("TQBR"))
            instrument.name must_== "Sberbank"
            instrument.instrumentType must_== "share"
            instrument.currency must_== Currency("RUB")
            val fetchedAt = instrument.updatedAt.toInstant
            (!fetchedAt.isBefore(before) && !fetchedAt.isAfter(after)) must beTrue
          }
          stored must_== found.toList
        }
      }

      "keeps no board for a broker record that names none" >> {
        val withoutBoard =
          InstrumentResponse(instrument = response.instrument.map(_.copy(classCode = "")))
        for {
          built <- directoryOver(Map(figi -> IO.pure(withoutBoard)))
          found <- built.directory.byKey(key)
          stored <- built.storage.all
        } yield {
          found.map(_.classCode) must beSome(Option.empty[ClassCode])
          stored.map(_.classCode) must_== List(None)
        }
      }

      "asks the broker for the key's id as a FIGI, without a board" >> {
        for {
          built <- directoryOver(Map(figi -> IO.pure(response)))
          _ <- built.directory.byKey(key)
          calls <- built.instruments.getInstrumentByCalls
        } yield {
          calls.map(_.idType) must_== List(InstrumentIdType.INSTRUMENT_ID_TYPE_FIGI)
          calls.map(_.id) must_== List(figi)
          calls.map(_.classCode) must_== List(None)
        }
      }

      "answers an instrument it has fetched from storage" >> {
        for {
          built <- directoryOver(Map(figi -> IO.pure(response)))
          first <- built.directory.byKey(key)
          second <- built.directory.byKey(key)
          calls <- built.instruments.getInstrumentByCalls
        } yield {
          second must_== first
          calls must haveSize(1)
        }
      }

      "reports an instrument the broker does not know as absent and stores nothing" >> {
        for {
          built <- directoryOver(Map(figi -> IO.raiseError(Status.NOT_FOUND.asRuntimeException())))
          found <- built.directory.byKey(key)
          stored <- built.storage.all
        } yield {
          found must beNone
          stored must beEmpty
        }
      }

      "reports a broker answer without an instrument as absent and stores nothing" >> {
        for {
          built <- directoryOver(Map(figi -> IO.pure(InstrumentResponse(instrument = None))))
          found <- built.directory.byKey(key)
          stored <- built.storage.all
        } yield {
          found must beNone
          stored must beEmpty
        }
      }

      "failures" >> {

        "reports an exhausted quota as rate limiting" >> {
          for {
            built <- directoryOver(
              Map(figi -> IO.raiseError(Status.RESOURCE_EXHAUSTED.asRuntimeException()))
            )
            result <- built.directory.byKey(key).attempt
          } yield result must beLeft[Throwable](MarketDataError.RateLimited(None))
        }

        "reports an unavailable broker as a transient failure" >> {
          for {
            built <- directoryOver(
              Map(figi -> IO.raiseError(Status.UNAVAILABLE.asRuntimeException()))
            )
            result <- built.directory.byKey(key).attempt
          } yield result must beLeft.like { case _: MarketDataError.Transient => ok }
        }

        "reports any other broker failure as a generic failure and stores nothing" >> {
          for {
            built <- directoryOver(
              Map(figi -> IO.raiseError(Status.PERMISSION_DENIED.asRuntimeException()))
            )
            result <- built.directory.byKey(key).attempt
            stored <- built.storage.all
          } yield {
            result must beLeft.like { case _: MarketDataError.Other => ok }
            stored must beEmpty
          }
        }
      }
    }
  }
}
