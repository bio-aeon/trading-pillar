package st.btu.trading.pillar.gateways

import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.show.*
import cats.{Functor, Monad}
import io.circe.{Decoder, Json}
import io.circe.parser.decode
import sttp.client3.*
import sttp.model.{Part, Uri}
import st.btu.trading.pillar.models.tg
import tofu.kernel.types.Throws
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets

final class TgGatewayImpl[F[_]: Logging](token: tg.Token, backend: SttpBackend[F, Any])(implicit
  F: Monad[F],
  R: Throws[F]
) extends TgGateway[F]
    with GatewayLogging {
  def sendMessage(chatId: tg.ChatId, msg: String): F[Unit] = {
    val queryString =
      s"chat_id=${chatId.show}&parse_mode=${TgGatewayImpl.ParseMode}&text=${URLEncoder
          .encode(msg, StandardCharsets.UTF_8)}"
    sendRequest("sendMessage", queryString, checkStatus)
  }

  def sendMessageWithKeyboard(
    chatId: tg.ChatId,
    msg: String,
    rows: List[List[tg.InlineButton]]
  ): F[Unit] = {
    val replyMarkup = URLEncoder.encode(keyboardJson(rows).noSpaces, StandardCharsets.UTF_8)
    val queryString =
      s"chat_id=${chatId.show}&parse_mode=${TgGatewayImpl.ParseMode}&text=${URLEncoder
          .encode(msg, StandardCharsets.UTF_8)}" +
        s"&reply_markup=$replyMarkup"
    sendRequest("sendMessage", queryString, checkStatus)
  }

  def answerCallbackQuery(callbackQueryId: String): F[Unit] = {
    val queryString =
      s"callback_query_id=${URLEncoder.encode(callbackQueryId, StandardCharsets.UTF_8)}"
    sendRequest("answerCallbackQuery", queryString, checkStatus)
  }

  def getUpdates(offset: Long): F[tg.Result[List[tg.Update]]] = {
    val allowedUpdates =
      URLEncoder.encode("""["message","callback_query"]""", StandardCharsets.UTF_8)
    val queryString = s"offset=${offset + 1}&timeout=0.5&allowed_updates=$allowedUpdates"
    sendRequest("getUpdates", queryString, parseResponse[tg.Result[List[tg.Update]]])
  }

  private def keyboardJson(rows: List[List[tg.InlineButton]]): Json =
    Json.obj(
      "inline_keyboard" -> Json.fromValues(rows.map(row => Json.fromValues(row.map(buttonJson))))
    )

  private def buttonJson(button: tg.InlineButton): Json =
    Json.obj(
      "text" -> Json.fromString(button.text),
      "callback_data" -> Json.fromString(button.callbackData)
    )

  def sendDocument(
    chatId: tg.ChatId,
    filename: String,
    content: Array[Byte],
    mimeType: String,
    caption: Option[String]
  ): F[Unit] = {
    val parts = List(
      multipart("chat_id", chatId.show),
      multipart("document", content).fileName(filename).contentType(mimeType)
    ) ++ caption.map(c => multipart("caption", c)).toList
    sendMultipart("sendDocument", parts)
  }

  def sendPhoto(
    chatId: tg.ChatId,
    filename: String,
    content: Array[Byte],
    caption: Option[String]
  ): F[Unit] = {
    val parts = List(
      multipart("chat_id", chatId.show),
      multipart("photo", content).fileName(filename).contentType(TgGatewayImpl.PngMimeType)
    ) ++ caption.map(c => multipart("caption", c)).toList
    sendMultipart("sendPhoto", parts)
  }

  private def sendMultipart(path: String, parts: Seq[Part[BasicRequestBody]]): F[Unit] = {
    val req = basicRequest
      .post(Uri(URI.create(s"${TgGatewayImpl.ApiBaseUrl}${token.show}/$path")))
      .multipartBody(parts)

    for {
      _ <- debug"${requestToString(req)}"
      resp <- req.send(backend)
      _ <- debug"${responseToString(resp)}"
      result <- checkStatus(resp)
    } yield result
  }

  private[gateways] def parseResponse[A: Decoder](
    response: Response[Either[String, String]]
  ): F[A] = {
    def parseContent(content: String): F[A] =
      decode[A](content)
        .fold(err => R.raise(new Exception(s"Failed to parse response: $err")), F.pure)

    if (response.isSuccess) {
      response.body match {
        case Right(content) => parseContent(content)
        case Left(_) => R.raise(new Exception("Empty response body"))
      }
    } else {
      R.raise(new Exception(s"Unexpected response status: ${response.code}"))
    }
  }

  private[gateways] def checkStatus(response: Response[Either[String, String]]): F[Unit] =
    Either
      .cond(
        response.isSuccess,
        F.unit,
        R.raise[Unit](new Exception(s"Unexpected response status: ${response.code}"))
      )
      .merge

  private[gateways] def sendRequest[Res](
    path: String,
    queryString: String,
    handleRes: Response[Either[String, String]] => F[Res]
  ): F[Res] = {
    val req = basicRequest
      .get(Uri(URI.create(s"${TgGatewayImpl.ApiBaseUrl}${token.show}/$path?$queryString")))

    for {
      _ <- debug"${requestToString(req)}"
      resp <- req.send(backend)
      _ <- debug"${responseToString(resp)}"
      result <- handleRes(resp)
    } yield result
  }
}

object TgGatewayImpl {

  private val ApiBaseUrl = "https://api.telegram.org/bot"
  private val ParseMode = "Markdown"
  private val PngMimeType = "image/png"

  def create[F[_]: Monad: Throws: Logging](
    token: tg.Token,
    backend: SttpBackend[F, Any]
  ): TgGatewayImpl[F] =
    new TgGatewayImpl[F](token, backend)

  def create[I[_]: Functor, F[_]: Monad: Throws](token: tg.Token, backend: SttpBackend[F, Any])(
    implicit logs: Logs[I, F]
  ): I[TgGatewayImpl[F]] =
    logs.forService[TgGateway[F]].map(implicit log => new TgGatewayImpl[F](token, backend))
}
