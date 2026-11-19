package st.btu.trading.pillar.gateways.binance

import cats.effect.{Async, Sync}
import cats.syntax.functor.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.gateways.CallPacer

/** The pacing of the calls that Binance limits per IP address, which every client calling from the
  * same address shares: build one and give it to the client of each account.
  */
final class BinanceIpBuckets[F[_]] private (private[binance] val pacers: Map[Bucket, CallPacer[F]])

object BinanceIpBuckets {

  /** Builds the pacing of the per-IP limits, with no call made yet. */
  def create[I[_]: Sync, F[_]: Async]: I[BinanceIpBuckets[F]] =
    pacersOf[I, F](Scope.Ip).map(new BinanceIpBuckets[F](_))

  // A pacer for each bucket counted against `scope`.
  private[binance] def pacersOf[I[_]: Sync, F[_]: Async](
    scope: Scope
  ): I[Map[Bucket, CallPacer[F]]] =
    Bucket.values.toList
      .filter(_.scope == scope)
      .traverse(bucket => CallPacer.create[I, F].map(bucket -> _))
      .map(_.toMap)
}
