package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.analytics.portfolio.*
import st.btu.trading.pillar.models.Account
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.storages.{
  AccountOperationStorage,
  AccountStorage,
  AllocationTargetStorage,
  PortfolioSnapshotStorage,
  PortfolioValuationStorage,
  TcsPortfolioOperationStorage
}

import java.time.{Instant, LocalDate, ZonedDateTime}
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

  // --- AccountOperationStorage ---

  // Serves the operations as the port promises: those that occurred from `since` until `until`,
  // oldest first, in ref order among those at the same instant.
  def accountOperationStorage(
    stored: List[AccountOperation] = List.empty
  ): IO[AccountOperationStorage[IO]] =
    IO.pure(new AccountOperationStorage[IO] {
      def operations(
        accountId: AccountId,
        since: Instant,
        until: Instant
      ): IO[Vector[AccountOperation]] =
        IO.pure(operationsInRange(stored, accountId, since, until))
    })

  private def operationsInRange(
    stored: List[AccountOperation],
    accountId: AccountId,
    since: Instant,
    until: Instant
  ): Vector[AccountOperation] =
    stored
      .filter { op =>
        op.accountId == accountId && !op.occurredAt.isBefore(since) && op.occurredAt.isBefore(until)
      }
      .sortBy(op => (op.occurredAt, op.ref.value))
      .toVector

  private val refCounter = new AtomicLong(0)

  // Terse fixture builder for an AccountOperation: `instrument` is the id of a Tinkoff instrument,
  // and every field a spec does not name gets an arbitrary but valid value, a ref of its own
  // included. `quantity` is signed, positive for what grows a position.
  def accountOperation(
    accountId: AccountId,
    kind: OperationKind,
    amount: BigDecimal,
    occurredAt: Instant,
    instrument: Option[String] = None,
    quantity: BigDecimal = 0,
    currency: String = "RUB",
    state: OperationState = OperationState.Executed,
    price: Option[BigDecimal] = None,
    brokerType: Option[String] = None
  ): AccountOperation =
    AccountOperation(
      accountId,
      OperationRef(f"ref-${refCounter.incrementAndGet()}%06d"),
      kind,
      state,
      occurredAt,
      instrument.map(MarketDataFakes.instrumentKey),
      quantity,
      price,
      amount,
      Currency(currency),
      BrokerOperationType(brokerType.getOrElse(kind.value))
    )

  // --- TcsPortfolioOperationStorage ---

  // Also the read port of the shared operations, over the translations the records carried.
  trait FakeTcsPortfolioOperationStorage
      extends TcsPortfolioOperationStorage[IO]
      with AccountOperationStorage[IO] {

    /** How many records each `record` call carried, oldest first. */
    def recordedBatchSizes: IO[List[Int]]
  }

  // `record` upserts as the contract says: an operation by (account, extId, operationType), keeping
  // the id it was stored under, and a translation by (account, ref).
  def tcsPortfolioOperationStorage(
    operations: List[TcsPortfolioOperation] = List.empty,
    translations: List[AccountOperation] = List.empty
  ): IO[FakeTcsPortfolioOperationStorage] =
    for {
      operationsRef <- Ref.of[IO, List[TcsPortfolioOperation]](operations)
      translationsRef <- Ref.of[IO, List[AccountOperation]](translations)
      batchSizesRef <- Ref.of[IO, List[Int]](List.empty)
    } yield new FakeTcsPortfolioOperationStorage {
      def lastOperation(accountId: AccountId): IO[Option[TcsPortfolioOperation]] =
        operationsRef.get
          .map(_.filter(_.accountId == accountId).maxByOption(_.createdAt.toInstant.toEpochMilli))

      def operationsByDtRange(
        accountId: AccountId,
        since: ZonedDateTime,
        until: ZonedDateTime
      ): IO[Set[TcsPortfolioOperation]] =
        operationsRef.get.map(_.filter { op =>
          op.accountId == accountId && !op.createdAt.isBefore(since) && op.createdAt
            .isBefore(until)
        }.toSet)

      def record(records: List[TcsOperationRecord]): IO[Unit] =
        batchSizesRef.update(_ :+ records.size) >>
          operationsRef.update(records.map(_.operation).foldLeft(_)(upsertOperation)) >>
          translationsRef.update(records.map(_.translation).foldLeft(_)(upsertTranslation))

      def operations(
        accountId: AccountId,
        since: Instant,
        until: Instant
      ): IO[Vector[AccountOperation]] =
        translationsRef.get.map(operationsInRange(_, accountId, since, until))

      def recordedBatchSizes: IO[List[Int]] = batchSizesRef.get
    }

  private def upsertOperation(
    stored: List[TcsPortfolioOperation],
    operation: TcsPortfolioOperation
  ): List[TcsPortfolioOperation] = {
    val at = stored.indexWhere(s =>
      s.accountId == operation.accountId && s.extId == operation.extId &&
        s.operationType == operation.operationType
    )
    if (at < 0) stored :+ operation else stored.updated(at, operation.copy(id = stored(at).id))
  }

  private def upsertTranslation(
    stored: List[AccountOperation],
    translation: AccountOperation
  ): List[AccountOperation] = {
    val at =
      stored.indexWhere(s => s.accountId == translation.accountId && s.ref == translation.ref)
    if (at < 0) stored :+ translation else stored.updated(at, translation)
  }

  private val idCounter = new AtomicLong(0)

  // Terse fixture builder for a TcsPortfolioOperation: every field a spec does not name gets an
  // arbitrary but valid value.
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

  // --- PortfolioSnapshotStorage ---

  trait FakePortfolioSnapshotStorage extends PortfolioSnapshotStorage[IO] {
    def all: IO[Map[(AccountId, LocalDate), PortfolioSnapshot]]
  }

  // Keyed as the storage contract says: a save replaces the snapshot of that account and date.
  def portfolioSnapshotStorage(): IO[FakePortfolioSnapshotStorage] =
    Ref.of[IO, Map[(AccountId, LocalDate), PortfolioSnapshot]](Map.empty).map { ref =>
      new FakePortfolioSnapshotStorage {
        def save(accountId: AccountId, snapshot: PortfolioSnapshot): IO[Unit] =
          ref.update(_.updated((accountId, snapshot.holdings.date), snapshot))

        def all: IO[Map[(AccountId, LocalDate), PortfolioSnapshot]] = ref.get
      }
    }

  // Saving a snapshot of `failingAccount` fails with `failure`; every other account saves as usual,
  // for specs of how a caller carries on after one account's save fails.
  def failingPortfolioSnapshotStorage(
    failingAccount: AccountId,
    failure: Throwable
  ): IO[FakePortfolioSnapshotStorage] =
    portfolioSnapshotStorage().map { working =>
      new FakePortfolioSnapshotStorage {
        def save(accountId: AccountId, snapshot: PortfolioSnapshot): IO[Unit] =
          if (accountId == failingAccount) IO.raiseError(failure)
          else working.save(accountId, snapshot)

        def all: IO[Map[(AccountId, LocalDate), PortfolioSnapshot]] = working.all
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
            IO.pure(PositionSnapshot(date, DefaultCurrency, Vector.empty, Zero, Zero, Zero, 0))
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
