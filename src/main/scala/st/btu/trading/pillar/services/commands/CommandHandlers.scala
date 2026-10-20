package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import st.btu.trading.pillar.facades.TcsFacade
import st.btu.trading.pillar.gateways.TgGateway

/** Assembles the handlers a bot serves. */
object CommandHandlers {

  /** The handlers of a complete bot, in the order `/help` lists them: `/ping` and `/info`, then
    * `extraHandlers`, then `/help` itself, which lists the usage of every handler before it.
    *
    * @param tgGateway
    *   where the built-in handlers send their replies; give `extraHandlers` the same gateway
    * @param tcsFacade
    *   the Tinkoff client `/info` reads positions from
    */
  def standard[F[_]: Monad: Clock](
    tgGateway: TgGateway[F],
    tcsFacade: TcsFacade[F],
    extraHandlers: List[CommandHandler[F]]
  ): List[CommandHandler[F]] = {
    val pingHandler = new PingHandler[F](tgGateway)
    val infoHandler = new InfoHandler[F](tgGateway, tcsFacade)
    val baseHandlers = List[CommandHandler[F]](pingHandler, infoHandler) ++ extraHandlers
    val helpHandler =
      new HelpHandler[F](tgGateway, baseHandlers.map(_.usage) :+ HelpHandler.Usage)
    baseHandlers :+ helpHandler
  }
}
