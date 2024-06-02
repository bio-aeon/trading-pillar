package su.wps.trading.pillar.gateways

import sttp.client3.{Request, Response}

trait GatewayLogging {
  protected def requestToString(request: Request[?, ?]) =
    s"HttpRequest(${request.method}, ${request.uri}, ${request.headers}, ${request.body})"

  protected def responseToString(response: Response[?]) =
    s"HttpResponse(${response.code}, ${response.body}, ${response.headers})"
}
