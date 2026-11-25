package st.btu.trading.pillar.gateways.tcs

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import com.google.protobuf.timestamp.Timestamp
import io.grpc.Status
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments
import ru.tinkoff.piapi.contract.v1.common.Quotation
import ru.tinkoff.piapi.contract.v1.instruments.*
import st.btu.trading.pillar.models.domain.{Broker, BrokerInstrumentId, Currency, InstrumentKey}
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.testkit.McpFakes.LogEntry
import st.btu.trading.pillar.testkit.{McpFakes, MarketDataFakes, TcsMarketDataFakes, VirtualTime}
import tofu.logging.{Logging, Logs}

import java.time.{Duration as JDuration, *}
import scala.concurrent.duration.*

private final case class BuiltTcsInstrumentDirectory(
  directory: TcsInstrumentDirectory[IO],
  instruments: TcsMarketDataFakes.FakeInstrumentsService,
  storage: MarketDataFakes.FakeBrokerInstrumentStorage,
  logged: IO[List[LogEntry]]
)

// A lookup of a stored future: the directory with its fakes, the record it had stored, what it
// answered, and the instant just before the lookup.
private final case class LookedUp(
  built: BuiltTcsInstrumentDirectory,
  record: BrokerInstrument,
  found: Option[BrokerInstrument],
  before: Instant
) {
  def renewed: Boolean = found.exists(!_.updatedAt.toInstant.isBefore(before))
}

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

  private val moscow = ZoneId.of("Europe/Moscow")

  private def directoryOver(
    byFigi: Map[String, IO[InstrumentResponse]],
    stored: List[BrokerInstrument] = Nil,
    futures: Map[String, IO[FutureResponse]] = Map.empty
  ): IO[BuiltTcsInstrumentDirectory] =
    for {
      instruments <- TcsMarketDataFakes.instrumentsService(byFigi = byFigi, futuresByFigi = futures)
      storage <- MarketDataFakes.brokerInstrumentStorage(stored)
      capturing <- McpFakes.capturingLogs
      (logs, logged) = capturing
      directory <- {
        implicit val directoryLogs: Logs[IO, IO] = logs
        TcsInstrumentDirectory.create[IO, IO](instruments, storage, moscow)
      }
    } yield BuiltTcsInstrumentDirectory(directory, instruments, storage, logged)

  private val futureFigi = "FUTSFH7"
  private val futureKey = InstrumentKey(Broker.Tinkoff, BrokerInstrumentId(futureFigi))

  private val futureInstrument = InstrumentResponse(instrument =
    Some(
      Instrument(
        figi = futureFigi,
        ticker = "SFH7",
        classCode = "SPBFUT",
        currency = "rub",
        name = "Sberbank 03.27",
        exchange = "FORTS",
        instrumentType = "futures"
      )
    )
  )

  private def quotation(units: Long, hundredths: Int = 0): Option[Quotation] =
    Some(Quotation(units, hundredths * 10000000))

  private def timestamp(instant: String): Option[Timestamp] =
    Some(Timestamp(Instant.parse(instant).getEpochSecond, 0))

  // A step of 0.01 worth 0.79, so a point is worth 79.
  private def future(
    basicAsset: String = "SBER",
    step: Option[Quotation] = quotation(0, 1),
    stepValue: Option[Quotation] = quotation(0, 79),
    lastTradeDate: Option[Timestamp] = timestamp("2027-03-17T21:00:00Z"),
    expirationDate: Option[Timestamp] = timestamp("2027-03-17T21:00:00Z")
  ): IO[FutureResponse] =
    IO.pure(
      FutureResponse(instrument =
        Some(
          Future(
            figi = futureFigi,
            basicAsset = basicAsset,
            minPriceIncrement = step,
            minPriceIncrementAmount = stepValue,
            lastTradeDate = lastTradeDate,
            expirationDate = expirationDate
          )
        )
      )
    )

  private val march18 = LocalDate.of(2027, 3, 18)

  private val terms =
    FutureTerms(Underlying("SBER"), march18, march18, BigDecimal("0.01"), BigDecimal("0.79"))

  // A stored record of the future, fetched `age` ago.
  private def storedFuture(
    age: JDuration,
    futureTerms: Option[FutureTerms] = Some(terms)
  ): IO[BrokerInstrument] =
    IO.realTimeInstant.map { now =>
      MarketDataFakes.brokerInstrument(
        futureFigi,
        Ticker("SFH7"),
        instrumentType = "futures",
        futureTerms = futureTerms,
        updatedAt = ZonedDateTime.ofInstant(now.minus(age), ZoneOffset.UTC)
      )
    }

  private def warnings(logged: List[LogEntry]): Int = logged.count(_.level == Logging.Warn)

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

      "terms of a future" >> {

        "are fetched by FIGI with the record and stored with it" >> {
          for {
            built <- directoryOver(
              Map(futureFigi -> IO.pure(futureInstrument)),
              futures = Map(futureFigi -> future())
            )
            found <- built.directory.byKey(futureKey)
            calls <- built.instruments.futureByCalls
            stored <- built.storage.all
          } yield {
            found.flatMap(_.futureTerms) must beSome(terms)
            calls.map(_.id) must_== List(futureFigi)
            calls.map(_.idType) must_== List(InstrumentIdType.INSTRUMENT_ID_TYPE_FIGI)
            stored must_== found.toList
          }
        }

        "are not asked of a record that is not a future" >> {
          for {
            built <- directoryOver(Map(figi -> IO.pure(response)))
            found <- built.directory.byKey(key)
            calls <- built.instruments.futureByCalls
          } yield {
            found.flatMap(_.futureTerms) must beNone
            calls must beEmpty
          }
        }

        "read a date given as the UTC midnight or the Moscow midnight as the same exchange day" >> {
          def datesOf(instant: String) =
            for {
              built <- directoryOver(
                Map(futureFigi -> IO.pure(futureInstrument)),
                futures = Map(
                  futureFigi -> future(
                    lastTradeDate = timestamp(instant),
                    expirationDate = timestamp(instant)
                  )
                )
              )
              found <- built.directory.byKey(futureKey)
            } yield found.flatMap(_.futureTerms).map(t => (t.lastTradeDate, t.expirationDate))

          for {
            utcMidnight <- datesOf("2027-03-17T21:00:00Z")
            moscowMidnight <- datesOf("2027-03-18T00:00:00Z")
          } yield {
            utcMidnight must beSome((march18, march18))
            moscowMidnight must beSome((march18, march18))
          }
        }

        "change day at the Moscow midnight" >> {
          for {
            built <- directoryOver(
              Map(futureFigi -> IO.pure(futureInstrument)),
              futures = Map(
                futureFigi -> future(
                  lastTradeDate = timestamp("2027-03-18T20:59:59Z"),
                  expirationDate = timestamp("2027-03-18T21:00:00Z")
                )
              )
            )
            found <- built.directory.byKey(futureKey)
          } yield found
            .flatMap(_.futureTerms)
            .map(t => (t.lastTradeDate, t.expirationDate)) must beSome(
            (march18, march18.plusDays(1))
          )
        }

        "are left out, the record being stored without them" >> {
          Fragments.foreach(
            List(
              "for a step value of zero" -> future(stepValue = quotation(0)),
              "for a step of zero" -> future(step = quotation(0)),
              "for a negative step" -> future(step = Some(Quotation(-1, 0))),
              "for an empty underlying" -> future(basicAsset = ""),
              "for a missing date" -> future(expirationDate = None),
              "for a contract the broker does not know" ->
                IO.raiseError(Status.NOT_FOUND.asRuntimeException())
            )
          ) { case (answer, response) =>
            answer >> {
              for {
                built <- directoryOver(
                  Map(futureFigi -> IO.pure(futureInstrument)),
                  futures = Map(futureFigi -> response)
                )
                found <- built.directory.byKey(futureKey)
                stored <- built.storage.all
              } yield {
                found.map(_.futureTerms) must_== Some(None)
                stored.map(_.futureTerms) must_== List(None)
              }
            }
          }
        }

        "fail the lookup when the broker fails, and nothing is stored" >> {
          for {
            built <- directoryOver(
              Map(futureFigi -> IO.pure(futureInstrument)),
              futures =
                Map(futureFigi -> IO.raiseError(Status.PERMISSION_DENIED.asRuntimeException()))
            )
            result <- built.directory.byKey(futureKey).attempt
            stored <- built.storage.all
          } yield {
            result must beLeft.like { case _: MarketDataError.Other => ok }
            stored must beEmpty
          }
        }
      }

      "refreshing the record of a future" >> {

        // The directory answers with the same record, as it had stored it.
        def lookUp(stored: IO[BrokerInstrument], answer: IO[FutureResponse]): IO[LookedUp] =
          for {
            record <- stored
            built <- directoryOver(
              Map(futureFigi -> IO.pure(futureInstrument)),
              stored = List(record),
              futures = Map(futureFigi -> answer)
            )
            before <- IO.realTimeInstant
            found <- built.directory.byKey(futureKey)
          } yield LookedUp(built, record, found, before)

        val refreshedTerms = terms.copy(priceStepValue = BigDecimal("0.85"))
        val answerWithNewValue = future(stepValue = quotation(0, 85))

        "asks again a record that is 25 hours old and renews its time" >> {
          for {
            looked <- lookUp(storedFuture(JDuration.ofHours(25)), answerWithNewValue)
            calls <- looked.built.instruments.futureByCalls
            stored <- looked.built.storage.all
          } yield {
            calls must haveSize(1)
            looked.found.flatMap(_.futureTerms) must beSome(refreshedTerms)
            looked.renewed must beTrue
            stored must_== looked.found.toList
          }
        }

        "does not ask a record that is 23 hours old" >> {
          for {
            looked <- lookUp(storedFuture(JDuration.ofHours(23)), answerWithNewValue)
            calls <- looked.built.instruments.futureByCalls
          } yield {
            calls must beEmpty
            looked.found must beSome(looked.record)
          }
        }

        "does not ask for a contract that has expired in the exchange zone" >> {
          for {
            now <- IO.realTimeInstant
            yesterday = now.atZone(moscow).toLocalDate.minusDays(1)
            looked <- lookUp(
              storedFuture(
                JDuration.ofHours(25),
                Some(terms.copy(lastTradeDate = yesterday, expirationDate = yesterday))
              ),
              answerWithNewValue
            )
            calls <- looked.built.instruments.futureByCalls
          } yield {
            calls must beEmpty
            looked.found must beSome(looked.record)
          }
        }

        "does not ask for a record that is not a future, however old" >> {
          val share = IO.pure(
            MarketDataFakes.brokerInstrument(
              futureFigi,
              Ticker("SBER"),
              updatedAt = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
            )
          )
          for {
            looked <- lookUp(share, answerWithNewValue)
            calls <- looked.built.instruments.futureByCalls
          } yield {
            calls must beEmpty
            looked.found must beSome(looked.record)
          }
        }

        "gives a record that has none its terms when it is asked again" >> {
          lookUp(storedFuture(JDuration.ofHours(25), None), future()).map { looked =>
            looked.found.flatMap(_.futureTerms) must beSome(terms)
            looked.renewed must beTrue
          }
        }

        "does not ask a record without terms that is 23 hours old" >> {
          for {
            looked <- lookUp(storedFuture(JDuration.ofHours(23), None), future())
            calls <- looked.built.instruments.futureByCalls
          } yield {
            calls must beEmpty
            looked.found.flatMap(_.futureTerms) must beNone
          }
        }

        "keeps the stored terms, renews the time and logs an answer without terms" >> {
          Fragments.foreach(
            List(
              "with a step of zero" -> future(step = quotation(0)),
              "saying the contract is unknown" ->
                IO.raiseError(Status.NOT_FOUND.asRuntimeException())
            )
          ) { case (answer, response) =>
            answer >> {
              for {
                looked <- lookUp(storedFuture(JDuration.ofHours(25)), response)
                logged <- looked.built.logged
                stored <- looked.built.storage.all
              } yield {
                looked.found.flatMap(_.futureTerms) must beSome(terms)
                looked.renewed must beTrue
                stored must_== looked.found.toList
                warnings(logged) must_== 1
              }
            }
          }
        }

        "keeps the stored record, leaves its time and logs a failed call" >> {
          for {
            looked <- lookUp(
              storedFuture(JDuration.ofHours(25)),
              IO.raiseError(Status.UNAVAILABLE.asRuntimeException())
            )
            logged <- looked.built.logged
            stored <- looked.built.storage.all
          } yield {
            looked.found must beSome(looked.record)
            stored must_== List(looked.record)
            warnings(logged) must_== 1
          }
        }

        "keeps the stored record, leaves its time and logs a call that takes over 10 seconds" >> {
          VirtualTime.run(for {
            looked <- lookUp(storedFuture(JDuration.ofHours(25)), IO.never)
            logged <- looked.built.logged
            stored <- looked.built.storage.all
            elapsed <- IO.monotonic
          } yield {
            looked.found must beSome(looked.record)
            stored must_== List(looked.record)
            warnings(logged) must_== 1
            elapsed must_== 10.seconds
          })
        }
      }
    }
  }
}
