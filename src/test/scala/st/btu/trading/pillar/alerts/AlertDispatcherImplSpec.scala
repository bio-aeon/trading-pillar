package st.btu.trading.pillar.alerts

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.storages.AlertHistoryStorage
import st.btu.trading.pillar.testkit.AlertFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

import java.time.{LocalTime, ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*

class AlertDispatcherImplSpec extends Specification with CatsEffect {

  private val ticker = Ticker("AAPL")
  private val t0 = ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC)
  private val channels = List("test")
  private val policy = DeliveryPolicy(cooldown = 10.minutes)

  private def alert(
    ruleKey: String = "price:1",
    kind: AlertKind = AlertKind.TargetHit,
    triggerValue: BigDecimal = 101,
    at: ZonedDateTime = t0,
    title: String = "AAPL target hit",
    body: String = "AAPL rose above 100"
  ): Alert = Alert(ruleKey, Some(ticker), kind, title, body, Some(triggerValue), at)

  private def buildDispatcher(
    channels: List[AlertChannel[IO]] = List.empty,
    history: AlertHistoryStorage[IO]
  ): IO[AlertDispatcherImpl[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[AlertDispatcher[IO]])
    AlertDispatcherImpl.create[IO, IO](channels, history)
  }

  "AlertDispatcherImpl" >> {
    "dispatch" >> {
      "delivers through the requested channel and records it" >> {
        for {
          channel <- AlertFakes.alertChannel("telegram")
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(channel), history)
          outcome <- dispatcher.dispatch(alert(), policy, List("telegram"))
          sent <- channel.sentAlerts
          recorded <- history.all
        } yield {
          outcome must_== DispatchOutcome.Delivered(List("telegram"))
          sent must haveSize(1)
          recorded must haveSize(1)
          recorded.head.suppressedBy must beNone
        }
      }

      "drops an unknown channel name" >> {
        for {
          good <- AlertFakes.alertChannel("telegram")
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(good), history)
          outcome <- dispatcher.dispatch(alert(), policy, List("telegram", "sms"))
        } yield outcome must_== DispatchOutcome.Delivered(List("telegram"))
      }

      "continues past a failing channel" >> {
        for {
          good <- AlertFakes.alertChannel("telegram")
          bad <- AlertFakes.failingAlertChannel("email")
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(good, bad), history)
          outcome <- dispatcher.dispatch(alert(), policy, List("telegram", "email"))
        } yield outcome must_== DispatchOutcome.Delivered(List("telegram"))
      }

      "fires at most once per cooldown for a threshold rule oscillating across the boundary" >> {
        for {
          channel <- AlertFakes.alertChannel()
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(channel), history)
          first <- dispatcher.dispatch(alert(triggerValue = 101, at = t0), policy, channels)
          // Still within the cooldown window: suppressed even though the price (and so the
          // content/dedup hash) differs.
          second <- dispatcher.dispatch(
            alert(triggerValue = 110, at = t0.plusMinutes(5)),
            policy,
            channels
          )
          // Past the cooldown window: delivered again.
          third <- dispatcher.dispatch(
            alert(triggerValue = 110, at = t0.plusMinutes(11)),
            policy,
            channels
          )
        } yield {
          first must_== DispatchOutcome.Delivered(channels)
          second must_== DispatchOutcome.Suppressed(AlertDispatcherImpl.Reason.Cooldown)
          third must_== DispatchOutcome.Delivered(channels)
        }
      }

      "suppresses a second rule's alert as a dedup when its content matches one already delivered" >> {
        for {
          channel <- AlertFakes.alertChannel()
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(channel), history)
          first <- dispatcher.dispatch(
            alert(ruleKey = "price:1", triggerValue = 101, at = t0),
            policy,
            channels
          )
          // Different rule (so the cooldown key differs), identical content -> caught by dedup.
          second <- dispatcher.dispatch(
            alert(ruleKey = "price:2", triggerValue = 101, at = t0.plusMinutes(1)),
            policy,
            channels
          )
        } yield {
          first must_== DispatchOutcome.Delivered(channels)
          second must_== DispatchOutcome.Suppressed(AlertDispatcherImpl.Reason.Dedup)
        }
      }

      "delivers a second strategy warning within the cooldown" >> {
        def warning(distance: String, at: ZonedDateTime): Alert =
          alert(
            ruleKey = "strategy:1",
            kind = AlertKind.StrategyPreBuy,
            triggerValue = BigDecimal(distance),
            at = at,
            body = s"distance $distance"
          )
        for {
          channel <- AlertFakes.alertChannel()
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(channel), history)
          _ <- dispatcher.dispatch(warning("0.004", t0), policy, channels)
          second <- dispatcher.dispatch(warning("0.002", t0.plusMinutes(5)), policy, channels)
        } yield second must_== DispatchOutcome.Delivered(channels)
      }

      "re-arms a threshold rule only once price has moved beyond the hysteresis band" >> {
        val policy = DeliveryPolicy(cooldown = Duration.Zero, hysteresisPct = BigDecimal(1))
        for {
          channel <- AlertFakes.alertChannel()
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(channel), history)
          first <- dispatcher.dispatch(alert(triggerValue = 101, at = t0), policy, channels)
          // 0.3% away from 101: inside the 1% hysteresis band, so it does not re-arm.
          second <- dispatcher.dispatch(
            alert(
              triggerValue = BigDecimal("101.3"),
              at = t0.plusSeconds(1),
              body = "still near 100"
            ),
            policy,
            channels
          )
          // ~2% away from 101: outside the band, re-arms and delivers.
          third <- dispatcher.dispatch(
            alert(triggerValue = 103, at = t0.plusSeconds(2), body = "moved further"),
            policy,
            channels
          )
        } yield {
          first must_== DispatchOutcome.Delivered(channels)
          second must_== DispatchOutcome.Suppressed(AlertDispatcherImpl.Reason.Hysteresis)
          third must_== DispatchOutcome.Delivered(channels)
        }
      }

      "does not apply hysteresis to non-threshold (strategy) alert kinds" >> {
        val policy = DeliveryPolicy(cooldown = Duration.Zero, hysteresisPct = BigDecimal(50))
        for {
          channel <- AlertFakes.alertChannel()
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(channel), history)
          first <- dispatcher.dispatch(
            alert(kind = AlertKind.StrategyBuy, triggerValue = 100, at = t0, body = "buy 1"),
            policy,
            channels
          )
          second <- dispatcher.dispatch(
            alert(
              kind = AlertKind.StrategyBuy,
              triggerValue = 100.01,
              at = t0.plusSeconds(1),
              body = "buy 2"
            ),
            policy,
            channels
          )
        } yield {
          first must_== DispatchOutcome.Delivered(channels)
          second must_== DispatchOutcome.Delivered(channels)
        }
      }

      "suppresses once the daily cap is reached and allows it again from a fresh count" >> {
        val policy = DeliveryPolicy(cooldown = Duration.Zero, maxPerDay = Some(2))
        def strategyAlert(n: Int, at: ZonedDateTime) =
          alert(
            ruleKey = "binding:9",
            kind = AlertKind.StrategyBuy,
            triggerValue = 100 + n,
            at = at,
            body = s"buy $n"
          )
        for {
          channel <- AlertFakes.alertChannel()
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(channel), history)
          r1 <- dispatcher.dispatch(strategyAlert(1, t0), policy, channels)
          r2 <- dispatcher.dispatch(strategyAlert(2, t0.plusMinutes(1)), policy, channels)
          r3 <- dispatcher.dispatch(strategyAlert(3, t0.plusMinutes(2)), policy, channels)
        } yield {
          r1 must_== DispatchOutcome.Delivered(channels)
          r2 must_== DispatchOutcome.Delivered(channels)
          r3 must_== DispatchOutcome.Suppressed(AlertDispatcherImpl.Reason.DailyCap)
        }
      }

      "suppresses alerts that fall inside the configured quiet hours, including overnight wraparound" >> {
        val quietHours = QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), ZoneOffset.UTC)
        val policy = DeliveryPolicy(cooldown = Duration.Zero, quietHours = Some(quietHours))
        val daytime = ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        val nighttime = ZonedDateTime.of(2024, 1, 1, 23, 0, 0, 0, ZoneOffset.UTC)
        def strategyAlert(at: ZonedDateTime, body: String) =
          alert(ruleKey = "binding:5", kind = AlertKind.StrategyBuy, at = at, body = body)
        for {
          channel <- AlertFakes.alertChannel()
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(channel), history)
          day <- dispatcher.dispatch(strategyAlert(daytime, "daytime"), policy, channels)
          night <- dispatcher.dispatch(strategyAlert(nighttime, "nighttime"), policy, channels)
        } yield {
          day must_== DispatchOutcome.Delivered(channels)
          night must_== DispatchOutcome.Suppressed(AlertDispatcherImpl.Reason.QuietHours)
        }
      }

      "delivers a System alert inside quiet hours while a non-System alert there is suppressed" >> {
        val quietHours = QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), ZoneOffset.UTC)
        val policy = DeliveryPolicy(cooldown = Duration.Zero, quietHours = Some(quietHours))
        val nighttime = ZonedDateTime.of(2024, 1, 1, 23, 0, 0, 0, ZoneOffset.UTC)
        for {
          channel <- AlertFakes.alertChannel()
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(channel), history)
          systemOutcome <- dispatcher.dispatch(
            alert(ruleKey = "system:sync-failed:AAPL", kind = AlertKind.System, at = nighttime),
            policy,
            channels
          )
          nonSystemOutcome <- dispatcher.dispatch(
            alert(ruleKey = "price:7", kind = AlertKind.TargetHit, at = nighttime),
            policy,
            channels
          )
        } yield {
          systemOutcome must_== DispatchOutcome.Delivered(channels)
          nonSystemOutcome must_== DispatchOutcome.Suppressed(AlertDispatcherImpl.Reason.QuietHours)
        }
      }

      "suppresses as delivery_failed when every requested channel fails to send, without arming hysteresis" >> {
        val policy = DeliveryPolicy(cooldown = Duration.Zero, hysteresisPct = BigDecimal(1))
        for {
          failing <- AlertFakes.failingAlertChannel("email")
          good <- AlertFakes.alertChannel("telegram")
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(failing, good), history)
          failed <- dispatcher.dispatch(alert(triggerValue = 101, at = t0), policy, List("email"))
          recorded <- history.all
          // Same triggerValue, a channel that works this time: if the failed attempt above had
          // armed hysteresis, this would be suppressed as Hysteresis instead of delivered.
          retried <- dispatcher.dispatch(
            alert(triggerValue = 101, at = t0.plusSeconds(1)),
            policy,
            List("telegram")
          )
        } yield {
          failed must_== DispatchOutcome.Suppressed(AlertDispatcherImpl.Reason.DeliveryFailed)
          recorded must haveSize(1)
          recorded.head.suppressedBy must beSome(AlertDispatcherImpl.Reason.DeliveryFailed)
          recorded.head.channels must beEmpty
          retried must_== DispatchOutcome.Delivered(List("telegram"))
        }
      }

      "suppresses every alert while muted, and resumes once unmuted" >> {
        val policy = DeliveryPolicy(cooldown = Duration.Zero)
        for {
          channel <- AlertFakes.alertChannel()
          history <- AlertFakes.alertHistoryStorage()
          dispatcher <- buildDispatcher(List(channel), history)
          _ <- dispatcher.mute(t0.plusHours(1).toInstant)
          muted <- dispatcher.dispatch(alert(at = t0.plusMinutes(1)), policy, channels)
          _ <- dispatcher.unmute
          resumed <- dispatcher.dispatch(
            alert(at = t0.plusMinutes(2), body = "after unmute"),
            policy,
            channels
          )
        } yield {
          muted must_== DispatchOutcome.Suppressed(AlertDispatcherImpl.Reason.Mute)
          resumed must_== DispatchOutcome.Delivered(channels)
        }
      }
    }
  }
}
