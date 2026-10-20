package st.btu.trading.pillar.mcp

import cats.~>
import cats.data.Kleisli
import cats.effect.std.Dispatcher
import cats.effect.testing.specs2.CatsEffect
import cats.effect.{Deferred, IO}
import cats.syntax.functor.*
import org.specs2.mutable.Specification

import scala.concurrent.duration.*

class EffectBridgeSpec extends Specification with CatsEffect {

  // A base effect other than IO: a program that reads an environment.
  private type I[A] = Kleisli[IO, Int, A]

  private val environment = 41

  // Runs `use` with the bridge to IO of a dispatcher that is open for as long as `use` runs.
  private def withBridge[A](use: (I ~> IO) => IO[A]): IO[A] =
    Dispatcher
      .parallel[I]
      .use(dispatcher => Kleisli.liftF[IO, Int, A](use(EffectBridge.toIO(dispatcher))))
      .run(environment)

  "EffectBridge" >> {
    "toIO" >> {

      "returns the value of the program" >> {
        withBridge(toIO => toIO(Kleisli.ask[IO, Int].map(_ + 1))).map(_ must_== 42)
      }

      "fails with the error of the program" >> {
        val boom = new RuntimeException("boom")
        withBridge(toIO => toIO(Kleisli.liftF[IO, Int, Int](IO.raiseError(boom))).attempt)
          .map(_ must beLeft[Throwable](boom))
      }

      "cancels the program when the IO is cancelled" >> {
        withBridge { toIO =>
          for {
            started <- Deferred[IO, Unit]
            cancelled <- Deferred[IO, Unit]
            program = Kleisli.liftF[IO, Int, Unit](
              started.complete(()) >> IO.never[Unit].onCancel(cancelled.complete(()).void)
            )
            fiber <- toIO(program).start
            _ <- started.get
            _ <- fiber.cancel
            result <- cancelled.get.timeout(5.seconds).attempt
          } yield result must beRight
        }
      }
    }
  }
}
