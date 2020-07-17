package ee.cone.c4gate_server

import ee.cone.c4actor.Context
import ee.cone.c4gate.HttpProtocol.S_HttpRequest

trait SeqPostHandler {
  def url: String
  def handle(request: S_HttpRequest): Context=>Context
  def handleError(request: S_HttpRequest, error: Throwable): Context=>Context
}
