package st.btu.trading.pillar.gateways

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Ref}
import cats.syntax.parallel.*
import org.specs2.mutable.Specification

import scala.concurrent.duration.*

class CallPacerSpec extends Specification with CatsEffect {

  // A call can be seen a little after its turn, which shifts it without moving the turn after it,
  // so a gap is allowed to fall a little short of the spacing.
  private val Tolerance = 30.millis

  // Runs a few calls on a pacer of its own, so that the code paths are warm when a spec measures.
  private val warmUp: IO[Unit] =
    CallPacer.create[IO, IO].flatMap(pacer => pacer.awaitTurn(1.milli) >> pacer.awaitTurn(1.milli))

  // The time at which each call got its turn, in the order the turns came.
  private def turns(
    pacer: CallPacer[IO],
    spacings: List[FiniteDuration]
  ): IO[List[FiniteDuration]] =
    for {
      times <- Ref.of[IO, List[FiniteDuration]](List.empty)
      _ <- spacings.foldLeft(IO.unit) { (before, spacing) =>
        before >> pacer.awaitTurn(spacing) >> IO.monotonic.flatMap(now => times.update(_ :+ now))
      }
      recorded <- times.get
    } yield recorded

  private def gaps(times: List[FiniteDuration]): List[FiniteDuration] =
    times.zip(times.drop(1)).map { case (earlier, later) => later - earlier }

  "CallPacer" >> {
    "awaitTurn" >> {
      "lets the first call through at once, whatever spacing it asks for the next" >> {
        for {
          _ <- warmUp
          pacer <- CallPacer.create[IO, IO]
          started <- IO.monotonic
          _ <- pacer.awaitTurn(5.seconds)
          waited <- IO.monotonic.map(_ - started)
        } yield waited must be_<(1.second)
      }

      "keeps the next call as far away as the spacing the call before it asked for" >> {
        for {
          _ <- warmUp
          pacer <- CallPacer.create[IO, IO]
          times <- turns(pacer, List(300.millis, 60.millis, 60.millis))
        } yield {
          // the first call asked for 300 ms, and the second for 60 ms: the second call waits for the
          // first one's spacing, and the third only for the second one's
          gaps(times).head must be_>=(300.millis - Tolerance)
          gaps(times)(1) must be_>=(60.millis - Tolerance)
          gaps(times)(1) must be_<(300.millis - Tolerance)
        }
      }

      "queues calls that arrive together, each after the spacing of the one before" >> {
        val spacing = 100.millis
        for {
          _ <- warmUp
          pacer <- CallPacer.create[IO, IO]
          started <- IO.monotonic
          arrivals <- List.fill(4)(pacer.awaitTurn(spacing) >> IO.monotonic).parSequence
        } yield {
          val sorted = arrivals.sorted
          gaps(sorted).forall(_ >= spacing - Tolerance) must beTrue
          (sorted.last - started) must be_>=(3 * spacing - Tolerance)
        }
      }
    }
  }
}
