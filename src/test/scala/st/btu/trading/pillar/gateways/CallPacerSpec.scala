package st.btu.trading.pillar.gateways

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import cats.syntax.parallel.*
import cats.syntax.traverse.*
import org.specs2.mutable.Specification
import st.btu.trading.pillar.testkit.VirtualTime

import scala.concurrent.duration.*

class CallPacerSpec extends Specification with CatsEffect {

  // The time at which each call got its turn, in the order the turns came.
  private def turns(
    pacer: CallPacer[IO],
    spacings: List[FiniteDuration]
  ): IO[List[FiniteDuration]] =
    spacings.traverse(spacing => pacer.awaitTurn(spacing) >> IO.monotonic)

  "CallPacer" >> {
    "awaitTurn" >> {
      "lets the first call through at once, whatever spacing it asks for the next" >> {
        VirtualTime.run(for {
          pacer <- CallPacer.create[IO, IO]
          started <- IO.monotonic
          _ <- pacer.awaitTurn(5.seconds)
          waited <- IO.monotonic.map(_ - started)
        } yield waited must_== Duration.Zero)
      }

      "keeps the next call as far away as the spacing the call before it asked for" >> {
        // the first call asks for 300 ms, and the second for 60 ms: the second call waits for the
        // first one's spacing, and the third only for the second one's
        VirtualTime.run(for {
          pacer <- CallPacer.create[IO, IO]
          times <- turns(pacer, List(300.millis, 60.millis, 60.millis))
        } yield VirtualTime.gaps(times) must_== List(300.millis, 60.millis))
      }

      "queues calls that arrive together, each after the spacing of the one before" >> {
        val spacing = 100.millis
        VirtualTime.run(
          for {
            pacer <- CallPacer.create[IO, IO]
            started <- IO.monotonic
            arrivals <- List.fill(4)(pacer.awaitTurn(spacing) >> IO.monotonic).parSequence
          } yield arrivals.sorted.map(_ - started) must_==
            List(Duration.Zero, spacing, 2 * spacing, 3 * spacing)
        )
      }
    }
  }
}
