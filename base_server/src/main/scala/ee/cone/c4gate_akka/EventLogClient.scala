package ee.cone.c4gate_akka

import akka.NotUsed
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.{OverflowStrategy, SharedKillSwitch}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

class EventLogHandler(webSocketUtil: WebSocketUtil) extends AkkaRequestHandler {
  def pathPrefix: String = "/eventlog/"
  def handleAsync(req: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse] = ???
//
//  def createRoom(will: String, killSwitch: SharedKillSwitch): Flow[ByteString,ByteString,NotUsed] = {
//
//
//    val source = Source.fromFutureSource(commonSourcePromise.future)
//      .buffer(size = 2, overflowStrategy = OverflowStrategy.dropHead)
//      .keepAlive(1.seconds,()=>ByteString.empty)
//    val sink = Flow[ByteString].idleTimeout(5.seconds).to(Sink.ignore)
//    Flow.fromSinkAndSourceCoupled(sink,source)
//
//  }
}
