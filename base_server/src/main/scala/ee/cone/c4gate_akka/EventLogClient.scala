package ee.cone.c4gate_akka

import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Execution
import ee.cone.c4di.c4
import ee.cone.c4gate_server.{AlienExchangeState, AlienUtil}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

@c4("AkkaGatewayApp") final class EventLogHandler(
  webSocketUtil: WebSocketUtil, alienUtil: AlienUtil, execution: Execution,
) extends AkkaRequestHandler with LazyLogging {
  def pathPrefix: String = "/eventlog"
  def handleAsync(req: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse] =
    req.header[UpgradeToWebSocket].fold(Future.successful(HttpResponse(400, entity = ""))){ upgrade =>
      assert(req.uri.path.toString == pathPrefix)
      val stateP = Promise[AlienExchangeState]()
      val source = Source.unfoldAsync(stateP.future){ wasStF =>
        logger.info("01")
        for {
          wasSt <- wasStF
          _ = logger.info("02")
          (willSt,msg) = alienUtil.read(wasSt)
          _ = logger.info("03")
        } yield Option((Future.successful(willSt),ByteString(msg)))
      }
      val sink = Sink.foldAsync[Option[AlienExchangeState],ByteString](None)((wasStOpt, bs) => Future {
        logger.info("04+++")
        try {
          val willStOpt = alienUtil.send(wasStOpt, bs.utf8String)
          logger.info(s"06 ${willStOpt.nonEmpty}")
          for (st <- willStOpt if !stateP.isCompleted) execution.success(stateP, st)
          willStOpt
        } catch {
          case NonFatal(e) =>
            logger.error("hut",e)
            throw e
        }
      })
      val flow = Flow.fromSinkAndSourceCoupledMat(sink, source)(Keep.left).watchTermination(){ (nu,doneF) =>
          doneF.onComplete{ res =>
            for(st <- stateP.future) alienUtil.stop(st)
            logger.info(s"05+${res}")
          }
          nu
        }
      Future.successful(webSocketUtil.toResponse(upgrade, flow))
    }
}
