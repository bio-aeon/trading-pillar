package st.btu.trading.pillar.mcp

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import org.http4s.*
import org.http4s.headers.{Authorization, Connection}
import org.typelevel.ci.CIStringSyntax

// Lets through only the requests that present the token as `Authorization: Bearer <token>`; every
// other request, whatever its path, is answered with 401 and a Bearer challenge. The 401 closes the
// connection, because the request body is left unread and the connection could not be reused.
private[mcp] object BearerAuth {

  def apply(token: AccessToken)(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    Kleisli { request =>
      if (presents(request, token)) routes.run(request) else OptionT.pure[IO](unauthorized)
    }

  private def presents(request: Request[IO], token: AccessToken): Boolean =
    request.headers.get[Authorization].exists { header =>
      header.credentials match {
        case Credentials.Token(AuthScheme.Bearer, presented) => token.admits(presented)
        case _ => false
      }
    }

  private val unauthorized: Response[IO] =
    Response[IO](Status.Unauthorized)
      .putHeaders(Header.Raw(ci"WWW-Authenticate", "Bearer"), Connection(ci"close"))
}
