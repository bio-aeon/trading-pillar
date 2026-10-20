package st.btu.trading.pillar.gateways

import cats.Applicative
import cats.effect.{Ref, Sync}
import cats.syntax.functor.*
import st.btu.trading.pillar.models.tg

/** A [[TgGateway]] that sends nothing: it keeps what the handlers send, per chat, until the caller
  * collects it with [[take]]. It lets the command handlers answer a caller that is not a Telegram
  * chat.
  *
  * Nothing is ever sent to Telegram and no update ever arrives: [[getUpdates]] is an empty success
  * and [[answerCallbackQuery]] does nothing.
  */
final class CapturingTgGateway[F[_]] private (
  replies: Ref[F, Map[tg.ChatId, Vector[CapturedReply]]]
)(implicit F: Applicative[F])
    extends TgGateway[F] {

  def sendMessage(chatId: tg.ChatId, msg: String): F[Unit] =
    record(chatId, CapturedReply.Text(msg))

  def sendMessageWithKeyboard(
    chatId: tg.ChatId,
    msg: String,
    rows: List[List[tg.InlineButton]]
  ): F[Unit] =
    record(chatId, CapturedReply.TextWithKeyboard(msg, rows.map(_.map(_.text))))

  def answerCallbackQuery(callbackQueryId: String): F[Unit] = F.unit

  def sendDocument(
    chatId: tg.ChatId,
    filename: String,
    content: Array[Byte],
    mimeType: String,
    caption: Option[String]
  ): F[Unit] =
    record(chatId, CapturedReply.Document(filename, content, caption))

  def sendPhoto(
    chatId: tg.ChatId,
    filename: String,
    content: Array[Byte],
    caption: Option[String]
  ): F[Unit] =
    record(chatId, CapturedReply.Photo(content, caption))

  def getUpdates(offset: Long): F[tg.Result[List[tg.Update]]] =
    F.pure(tg.Result(ok = true, result = List.empty))

  /** Removes what was sent to `chatId` and returns it in the order it was sent. */
  def take(chatId: tg.ChatId): F[List[CapturedReply]] =
    replies.modify(all => (all - chatId, all.getOrElse(chatId, Vector.empty).toList))

  private def record(chatId: tg.ChatId, reply: CapturedReply): F[Unit] =
    replies.update(all => all.updated(chatId, all.getOrElse(chatId, Vector.empty) :+ reply))
}

object CapturingTgGateway {

  /** A gateway that has captured nothing yet. */
  def create[I[_]: Sync, F[_]: Sync]: I[CapturingTgGateway[F]] =
    Ref.in[I, F, Map[tg.ChatId, Vector[CapturedReply]]](Map.empty).map(new CapturingTgGateway[F](_))
}
