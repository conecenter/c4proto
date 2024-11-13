package ee.cone.c4gate_akka

import akka.NotUsed
import akka.http.scaladsl.model.ws.{Message, TextMessage, UpgradeToWebSocket}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Execution
import ee.cone.c4di.c4
import ee.cone.c4gate_server.{AlienExchangeState, AlienUtil}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}

@c4("AkkaGatewayApp") final class EventLogHandler(
  alienUtil: AlienUtil, execution: Execution, akkaMat: AkkaMat,
) extends AkkaRequestHandler with LazyLogging {
  def pathPrefix: String = "/eventlog"
  def handleAsync(req: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse] =
    req.header[UpgradeToWebSocket].fold(Future.successful(HttpResponse(400, entity = ""))){ upgrade =>
      assert(req.uri.path.toString == pathPrefix)
      val stateP = Promise[AlienExchangeState]()
      val source = Source.unfoldAsync(stateP.future){ wasStF =>
        for {
          wasSt <- wasStF
          (willSt,msg) = alienUtil.read(wasSt)
        } yield Option((Future.successful(willSt),msg))
      }.keepAlive(1.seconds,()=>"").map(TextMessage(_))
      val sink = Flow[Message]
        .mapAsync(1)(message=>
          for {
            mat <- akkaMat.get
            data <- message match { case m: TextMessage => m.toStrict(1.seconds)(mat) }
          } yield data
        )
        .idleTimeout(5.seconds)
        .foldAsync[Option[AlienExchangeState]](None)((wasStOpt, message) => Future {
          if(message.text.isEmpty) wasStOpt else {
            val willSt = alienUtil.send(message.text)
            assert(wasStOpt.forall(_==willSt))
            if(!stateP.isCompleted) execution.success(stateP, willSt)
            Option(willSt)
          }
        }).to(Sink.onComplete{
          res =>
            logger.info(s"ws ${res}")
            for(st <- stateP.future) alienUtil.stop(st)
        })
      val flow = Flow.fromSinkAndSourceCoupled(sink, source)
      Future.successful(upgrade.handleMessages(flow))
    }
}
