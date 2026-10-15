package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.analytics.portfolio.*
import st.btu.trading.pillar.models.Account
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.storages.{
  AccountStorage,
  AllocationTargetStorage,
  PortfolioValuationStorage,
  TcsPortfolioOperationStorage
}

import java.time.{LocalDate, ZonedDateTime}
import java.util.concurrent.atomic.AtomicLong

object AnalyticsFakes {

  // --- AccountStorage ---

  trait FakeAccountStorage extends AccountStorage[IO]

  def accountStorage(accounts: List[Account] = List.empty): IO[FakeAccountStorage] =
    accountStorageF(IO.pure(accounts))

  def accountStorageF(result: IO[List[Account]]): IO[FakeAccountStorage] =
    IO.pure(new FakeAccountStorage {
      def allAccounts: IO[List[Account]] = result
    })

  // --- TcsPortfolioOperationStorage ---

  trait FakeTcsPortfolioOperationStorage extends TcsPortfolioOperationStorage[IO]

  def tcsPortfolioOperationStorage(
    operations: List[TcsPortfolioOperation] = List.empty
  ): IO[FakeTcsPortfolioOperationStorage] =
    Ref.of[IO, List[TcsPortfolioOperation]](operations).map { ref =>
      new FakeTcsPortfolioOperationStorage {
        def lastOperation(accountId: AccountId): IO[Option[TcsPortfolioOperation]] =
          ref.get
            .map(_.filter(_.accountId == accountId).maxByOption(_.createdAt.toInstant.toEpochMilli))

        def operationsByDtRange(accountId: AccountId, since: ZonedDateTime, until: ZonedDateTime)
          : IO[Set[TcsPortfolioOperation]] =
          ref.get.map(_.filter { op =>
            op.accountId == accountId && !op.createdAt.isBefore(since) && op.createdAt
              .isBefore(until)
          }.toSet)

        def saveOperations(newOperations: List[TcsPortfolioOperation]): IO[Unit] =
          ref.update(_ ++ newOperations)
      }
    }

  // Terse fixture builder for a TcsPortfolioOperation; every field irrelevant to the valuation
  // pipeline gets an arbitrary but valid default.
  private val idCounter = new AtomicLong(0)

  def operation(
    accountId: AccountId,
    operationType: String,
    amount: BigDecimal,
    createdAt: ZonedDateTime,
    figi: Option[String] = None,
    quantity: Int = 0,
    currency: String = "RUB",
    status: String = "OPERATION_STATE_EXECUTED",
    price: BigDecimal = BigDecimal(0)
  ): TcsPortfolioOperation = {
    val n = idCounter.incrementAndGet()
    TcsPortfolioOperation(
      PortfolioOperationId(n),
      PortfolioOperationExtId(s"ext-$n"),
      PortfolioOperationType(operationType),
      None,
      PortfolioOperationStatus(status),
      figi.map(Figi(_)),
      Currency(currency),
      price = price,
      quantity = quantity,
      quantityRest = 0,
      amount = amount,
      ProtocolVersion("v1"),
      createdAt,
      accountId
    )
  }

  // --- PortfolioValuationStorage ---

  trait FakePortfolioValuationStorage extends PortfolioValuationStorage[IO] {
    def all: IO[Map[AccountId, (Currency, Vector[ValuationPoint])]]
  }

  def portfolioValuationStorage(): IO[FakePortfolioValuationStorage] =
    Ref.of[IO, Map[AccountId, (Currency, Vector[ValuationPoint])]](Map.empty).map { ref =>
      new FakePortfolioValuationStorage {
        def upsert(accountId: AccountId, currency: Currency, points: Vector[ValuationPoint])
          : IO[Int] =
          ref
            .update { m =>
              val (_, existing) = m.getOrElse(accountId, (currency, Vector.empty))
              val merged = (existing ++ points).map(p => p.date -> p).toMap
              m.updated(accountId, (currency, merged.values.toVector.sortBy(_.date.toEpochDay)))
            }
            .as(points.size)

        def series(accountId: AccountId, from: LocalDate, to: LocalDate)
          : IO[Vector[ValuationPoint]] =
          ref.get.map(
            _.get(accountId)
              .map(_._2.filter(p => !p.date.isBefore(from) && !p.date.isAfter(to)))
              .getOrElse(Vector.empty)
          )

        def latest(accountId: AccountId): IO[Option[ValuationPoint]] =
          ref.get.map(_.get(accountId).flatMap(_._2.maxByOption(_.date.toEpochDay)))

        def all: IO[Map[AccountId, (Currency, Vector[ValuationPoint])]] = ref.get
      }
    }

  // --- PortfolioValuationService (fake, used by AnalyticsProcessImplSpec and by
  // PositionsHandlerSpec/AllocationHandlerSpec via positionSnapshots) ---

  trait FakePortfolioValuationService extends PortfolioValuationService[IO] {
    def calls: IO[List[AccountId]]
  }

  def portfolioValuationService(
    outcomes: Map[AccountId, IO[ValuationSeries]] = Map.empty,
    positionSnapshots: Map[AccountId, IO[PositionSnapshot]] = Map.empty
  ): IO[FakePortfolioValuationService] =
    Ref.of[IO, List[AccountId]](List.empty).map { callsRef =>
      new FakePortfolioValuationService {
        def dailySeries(accountId: AccountId, from: LocalDate, to: LocalDate): IO[ValuationSeries] =
          callsRef.update(_ :+ accountId) >> outcomes.getOrElse(
            accountId,
            IO.pure(ValuationSeries(accountId, DefaultCurrency, Vector.empty, Set.empty, Set.empty))
          )

        def positionsAsOf(accountId: AccountId, date: LocalDate): IO[PositionSnapshot] =
          callsRef.update(_ :+ accountId) >> positionSnapshots.getOrElse(
            accountId,
            IO.pure(PositionSnapshot(date, DefaultCurrency, Vector.empty, Zero, Zero, 0))
          )

        def calls: IO[List[AccountId]] = callsRef.get
      }
    }

  private val Zero = BigDecimal(0)
  private val DefaultCurrency = Currency("RUB")

  // --- AllocationTargetStorage ---

  trait FakeAllocationTargetStorage extends AllocationTargetStorage[IO]

  // Mirrors the production storage contract: only this account's
  // enabled targets, ordered by dimension then key.
  def allocationTargetStorage(
    targets: List[AllocationTarget] = List.empty
  ): IO[FakeAllocationTargetStorage] =
    IO.pure(new FakeAllocationTargetStorage {
      def byAccount(accountId: AccountId): IO[List[AllocationTarget]] =
        IO.pure(
          targets
            .filter(t => t.accountId == accountId && t.enabled)
            .sortBy(t => (t.dimension.value, t.key))
        )
    })

  private val targetIdCounter = new AtomicLong(0)

  def allocationTarget(
    accountId: AccountId,
    dimension: Dimension,
    key: String,
    targetPct: BigDecimal,
    driftThresholdPct: BigDecimal = BigDecimal(5),
    enabled: Boolean = true
  ): AllocationTarget =
    AllocationTarget(
      AllocationTargetId(targetIdCounter.incrementAndGet()),
      accountId,
      dimension,
      key,
      targetPct,
      driftThresholdPct,
      enabled
    )
}
