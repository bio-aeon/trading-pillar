package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.alerts.*
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.{Quote, Source, Ticker}
import st.btu.trading.pillar.storages.{
  AlertHistoryStorage,
  AlertRuleStorage,
  StrategyBindingStorage
}
import st.btu.trading.pillar.strategy.{Strategy, StrategyId}

import java.time.{Instant, ZonedDateTime}

object AlertFakes {

  // --- AlertHistoryStorage ---

  trait FakeAlertHistoryStorage extends AlertHistoryStorage[IO] {
    def all: IO[List[AlertRecord]]
  }

  def alertHistoryStorage(initial: List[AlertRecord] = List.empty): IO[FakeAlertHistoryStorage] =
    Ref.of[IO, List[AlertRecord]](initial).map { ref =>
      new FakeAlertHistoryStorage {
        def lastDelivered(ruleKey: String, ticker: Option[Ticker], kind: AlertKind)
          : IO[Option[AlertRecord]] =
          ref.get.map(
            _.filter(r =>
              r.ruleKey == ruleKey && r.ticker == ticker && r.kind == kind && r.suppressedBy.isEmpty
            ).maxByOption(_.deliveredAt.toInstant.toEpochMilli)
          )

        def deliveredCountSince(ruleKey: String, since: ZonedDateTime): IO[Int] =
          ref.get.map(
            _.count(r =>
              r.ruleKey == ruleKey && r.suppressedBy.isEmpty && !r.deliveredAt.isBefore(since)
            )
          )

        def existsDedup(hash: String, since: ZonedDateTime): IO[Boolean] =
          ref.get.map(
            _.exists(r =>
              r.dedupHash == hash && r.suppressedBy.isEmpty && !r.deliveredAt.isBefore(since)
            )
          )

        def record(r: AlertRecord): IO[Unit] = ref.update(_ :+ r)

        def recent(limit: Int): IO[List[AlertRecord]] = ref.get.map(_.takeRight(limit).reverse)

        def all: IO[List[AlertRecord]] = ref.get
      }
    }

  // --- AlertRuleStorage / StrategyBindingStorage ---

  trait FakeAlertRuleStorage extends AlertRuleStorage[IO] {
    def all: IO[List[PriceCrossed]]
  }

  def alertRuleStorage(rules: List[PriceCrossed] = List.empty): IO[FakeAlertRuleStorage] =
    for {
      rulesRef <- Ref.of[IO, List[PriceCrossed]](rules)
      idRef <- Ref.of[IO, Long](rules.map(_.id.value).maxOption.getOrElse(0L))
    } yield new FakeAlertRuleStorage {
      def allEnabledPriceRules: IO[List[PriceCrossed]] = rulesRef.get

      def byAccount(accountId: AccountId): IO[List[PriceCrossed]] =
        rulesRef.get.map(_.filter(_.accountId == accountId))

      def insertPriceRule(rule: NewPriceRule): IO[AlertRuleId] =
        for {
          nextId <- idRef.updateAndGet(_ + 1)
          id = AlertRuleId(nextId)
          created = PriceCrossed(
            id,
            rule.accountId,
            rule.ticker,
            rule.threshold,
            rule.direction,
            rule.proximity,
            rule.channels,
            DeliveryPolicy(
              cooldown = rule.cooldown.getOrElse(DeliveryPolicy.DefaultCooldown),
              maxPerDay = rule.maxPerDay
            )
          )
          _ <- rulesRef.update(_ :+ created)
        } yield id

      def delete(accountId: AccountId, id: AlertRuleId): IO[Boolean] =
        rulesRef.modify { current =>
          val kept = current.filterNot(r => r.id == id && r.accountId == accountId)
          (kept, kept.size != current.size)
        }

      def all: IO[List[PriceCrossed]] = rulesRef.get
    }

  trait FakeStrategyBindingStorage extends StrategyBindingStorage[IO]

  def strategyBindingStorage(
    bindings: List[StrategyBinding] = List.empty
  ): IO[FakeStrategyBindingStorage] =
    IO.pure(new FakeStrategyBindingStorage {
      def allEnabled: IO[List[StrategyBinding]] = IO.pure(bindings)

      def byAccount(accountId: AccountId): IO[List[StrategyBinding]] =
        IO.pure(bindings.filter(_.accountId == accountId))
    })

  // --- LiveStrategyService (fake, used by AlertProcessImplSpec) ---

  trait FakeLiveStrategyService extends LiveStrategyService[IO] {
    def evaluatedRuleKeys: IO[List[String]]
  }

  def liveStrategyService(
    outcomes: Map[String, IO[LiveEvaluation]] = Map.empty
  ): IO[FakeLiveStrategyService] =
    Ref.of[IO, List[String]](List.empty).map { callsRef =>
      new FakeLiveStrategyService {
        def evaluate(binding: StrategyBinding, strategy: Strategy, now: ZonedDateTime)
          : IO[LiveEvaluation] =
          callsRef.update(_ :+ binding.ruleKey) >> outcomes
            .getOrElse(binding.ruleKey, IO.pure(LiveEvaluation(List.empty, List.empty)))

        def evaluatedRuleKeys: IO[List[String]] = callsRef.get
      }
    }

  // --- AlertDispatcher (fake, used by AlertProcessImplSpec) ---

  trait FakeAlertDispatcher extends AlertDispatcher[IO] {
    def dispatched: IO[List[Alert]]

    // Last value passed to mute (None if mute was never called, or unmute ran after it).
    def muteUntil: IO[Option[Instant]]

    def unmuteCalls: IO[Int]
  }

  def alertDispatcher(): IO[FakeAlertDispatcher] =
    for {
      ref <- Ref.of[IO, List[Alert]](List.empty)
      muteRef <- Ref.of[IO, Option[Instant]](None)
      unmuteCountRef <- Ref.of[IO, Int](0)
    } yield new FakeAlertDispatcher {
      def dispatch(
        alert: Alert,
        policy: DeliveryPolicy,
        channels: List[String]
      ): IO[DispatchOutcome] =
        ref.update(_ :+ alert).as(DispatchOutcome.Delivered(channels))

      def mute(until: Instant): IO[Unit] = muteRef.set(Some(until))

      def unmute: IO[Unit] = muteRef.set(None) >> unmuteCountRef.update(_ + 1)

      def dispatched: IO[List[Alert]] = ref.get

      def muteUntil: IO[Option[Instant]] = muteRef.get

      def unmuteCalls: IO[Int] = unmuteCountRef.get
    }

  // --- AlertChannel ---

  trait FakeAlertChannel extends AlertChannel[IO] {
    def sentAlerts: IO[List[Alert]]
  }

  def alertChannel(channelName: String = "test"): IO[FakeAlertChannel] =
    Ref.of[IO, List[Alert]](List.empty).map { ref =>
      new FakeAlertChannel {
        def name: String = channelName

        def send(alert: Alert): IO[Unit] = ref.update(_ :+ alert)

        def sentAlerts: IO[List[Alert]] = ref.get
      }
    }

  def failingAlertChannel(channelName: String = "test"): IO[FakeAlertChannel] =
    Ref.of[IO, List[Alert]](List.empty).map { ref =>
      new FakeAlertChannel {
        def name: String = channelName

        def send(alert: Alert): IO[Unit] =
          IO.raiseError(new RuntimeException(s"$channelName channel failure"))

        def sentAlerts: IO[List[Alert]] = ref.get
      }
    }

  // --- SystemAlerts (fake, used by PriceHistorySyncProcessSpec) ---

  final case class RaisedSystemAlert(name: String, title: String, body: String)

  trait FakeSystemAlerts extends SystemAlerts[IO] {
    def raised: IO[List[RaisedSystemAlert]]
  }

  def systemAlerts(): IO[FakeSystemAlerts] =
    Ref.of[IO, List[RaisedSystemAlert]](List.empty).map { ref =>
      new FakeSystemAlerts {
        def raise(name: String, title: String, body: String): IO[Unit] =
          ref.update(_ :+ RaisedSystemAlert(name, title, body))

        def raised: IO[List[RaisedSystemAlert]] = ref.get
      }
    }

  // --- Fixture builders ---

  val DefaultAccountId: AccountId = AccountId(1)

  def priceCrossed(
    id: Long = 1L,
    accountId: AccountId = DefaultAccountId,
    ticker: Ticker = Ticker("AAPL"),
    threshold: BigDecimal = 100,
    direction: PriceCrossed.Direction = PriceCrossed.Direction.Above,
    proximity: Option[Proximity] = None,
    channels: List[String] = List("test"),
    policy: DeliveryPolicy = DeliveryPolicy()
  ): PriceCrossed =
    PriceCrossed(
      AlertRuleId(id),
      accountId,
      ticker,
      threshold,
      direction,
      proximity,
      channels,
      policy
    )

  def strategyBinding(
    id: Long = 1L,
    accountId: AccountId = DefaultAccountId,
    strategyId: StrategyId = StrategyId("test-strategy"),
    ticker: Ticker = Ticker("AAPL"),
    mode: BindingMode = BindingMode.Signal,
    channels: List[String] = List("test"),
    policy: DeliveryPolicy = DeliveryPolicy()
  ): StrategyBinding =
    StrategyBinding(StrategyBindingId(id), accountId, strategyId, ticker, mode, channels, policy)

  def quote(
    ticker: Ticker,
    price: BigDecimal,
    asOf: ZonedDateTime,
    source: Source = Source.Yahoo
  ): Quote = Quote(ticker, price, "USD", asOf, source)
}
