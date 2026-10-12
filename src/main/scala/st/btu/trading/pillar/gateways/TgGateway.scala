package st.btu.trading.pillar.gateways

import st.btu.trading.pillar.models.tg

/** The subset of the Telegram Bot API this library calls. */
trait TgGateway[F[_]] {

  /** Sends a plain text message to `chatId`. */
  def sendMessage(chatId: tg.ChatId, msg: String): F[Unit]

  /** Same as [[sendMessage]], with an inline keyboard attached.
    *
    * @param rows
    *   the keyboard's rows of buttons, top to bottom, each row left to right
    */
  def sendMessageWithKeyboard(
    chatId: tg.ChatId,
    msg: String,
    rows: List[List[tg.InlineButton]]
  ): F[Unit]

  /** Acknowledges a callback query so the tapped button's client-side loading state clears. */
  def answerCallbackQuery(callbackQueryId: String): F[Unit]

  /** Uploads `content` to `chatId` as a document attachment named `filename`.
    *
    * @param caption
    *   shown alongside the file; not truncated by this method
    */
  def sendDocument(
    chatId: tg.ChatId,
    filename: String,
    content: Array[Byte],
    mimeType: String,
    caption: Option[String]
  ): F[Unit]

  /** Uploads `content` to `chatId`, rendered inline as an image rather than offered as a file
    * attachment. Always a PNG in this codebase, so there is no `mimeType` parameter.
    */
  def sendPhoto(
    chatId: tg.ChatId,
    filename: String,
    content: Array[Byte],
    caption: Option[String]
  ): F[Unit]

  /** Polls for updates after `offset`. */
  def getUpdates(offset: Long): F[tg.Result[List[tg.Update]]]
}
