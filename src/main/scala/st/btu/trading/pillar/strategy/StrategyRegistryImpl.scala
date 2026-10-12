package st.btu.trading.pillar.strategy

import cats.Monad
import cats.effect.{Ref, Sync}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

final class StrategyRegistryImpl[F[_]: Logging] private (ref: Ref[F, Map[StrategyId, Strategy]])(
  implicit F: Monad[F]
) extends StrategyRegistry[F] {

  def register(strategy: Strategy): F[Unit] =
    ref
      .modify(current => (current.updated(strategy.id, strategy), current.contains(strategy.id)))
      .flatMap(replaced =>
        F.whenA(replaced)(warn"Strategy ${strategy.id.value} already registered; replacing")
      )

  def get(id: StrategyId): F[Option[Strategy]] = ref.get.map(_.get(id))

  def list: F[List[Strategy]] = ref.get.map(_.values.toList)
}

object StrategyRegistryImpl {

  /** Wires a [[StrategyRegistry]] with an empty registry. */
  def create[I[_]: Sync, F[_]: Sync](implicit logs: Logs[I, F]): I[StrategyRegistry[F]] =
    for {
      ref <- Ref.in[I, F, Map[StrategyId, Strategy]](Map.empty)
      registry <- logs
        .forService[StrategyRegistry[F]]
        .map(implicit log => new StrategyRegistryImpl[F](ref))
    } yield registry
}
