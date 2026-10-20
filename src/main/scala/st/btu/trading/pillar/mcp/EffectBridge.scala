package st.btu.trading.pillar.mcp

import cats.~>
import cats.effect.IO
import cats.effect.std.Dispatcher

/** Runs the consumer's base effect `I`, the one that runs the bot's effect `F` once given a
  * context, inside `IO`.
  *
  * A consumer needs it when `I` is not `IO`: the HTTP transport runs on `IO` only, so the tools
  * served over it, which run the consumer's code, have to hand it to `IO`. The opposite direction,
  * lifting the HTTP server into `I`, is `cats.effect.LiftIO`, for instance `.mapK(LiftIO.liftK[I])`
  * on the server's resource.
  */
object EffectBridge {

  /** Runs an `I` program as an `IO`, on `dispatcher`, which must stay open while the `IO`s run.
    *
    * It goes through a cancelable future, so cancelling the `IO`, as a client that drops its
    * request does, cancels the `I` program and waits for its finalizers.
    */
  def toIO[I[_]](dispatcher: Dispatcher[I]): I ~> IO =
    new (I ~> IO) {
      def apply[A](program: I[A]): IO[A] =
        IO.fromFutureCancelable(IO.delay {
          val (future, cancel) = dispatcher.unsafeToFutureCancelable(program)
          (future, IO.fromFuture(IO.delay(cancel())))
        })
    }
}
