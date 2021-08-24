package su.wps.trading.pillar.gateways

import sttp.client3.{Request, Response}

trait GatewayLogging {
  protected def requestToString(request: Request[_, _]) =
    s"HttpRequest(${request.method}, ${request.uri}, ${request.headers}, ${request.body})"

  protected def responseToString(response: Response[_]) =
    s"HttpResponse(${response.code}, ${response.body}, ${response.headers})"
}
