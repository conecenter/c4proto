
package ee.cone.c4gate_akka

import ee.cone.c4actor.{Executable, Execution}

import java.nio.file.Paths
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

import akka.stream.{KillSwitch, KillSwitches, SharedKillSwitch}
import akka.stream.scaladsl.{Flow, Keep, MergeHub, Sink, Source}

import akka.http.scaladsl.model.{AttributeKeys, HttpRequest, HttpResponse}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, WebSocketUpgrade}

trait RoomFactory {
  def createRoom(will: RoomConf, killSwitch: SharedKillSwitch): Flow[String,String,NotUsed]
}

object Rooms {
  sealed trait ToMainMessage

  case class RoomFlowReq(
    roomKey: RoomKey, response: Promise[Option[Flow[String,String,NotUsed]]]Keep
  ) extends ToMainMessage

  case class RoomsConf[T<:Product](rooms: Map[RoomKey,T]) extends ToMainMessage

  type RoomKey = String

  class RoomAccess(
    conf: RoomConf,
    flow: Flow[String,String,NotUsed],
    killSwitch: KillSwitch
  )
}

import ee.cone.c4gate_akka.Rooms._

trait RoomsHandler {
  def handle(req: HttpRequest): Future[HttpResponse]
}

class RoomsManagerImpl(
  execution: Execution,
  akkaMat: AkkaMat,
)(
  val mainSink: Promise[Sink[ToMainMessage,NotUsed]]
) extends RoomsHandler with Executable {

  def configureRooms(
    rooms: Map[RoomKey,RoomConf], willRoomsConf: Map[RoomKey,RoomConf]
  ): Map[RoomKey,RoomConf] =
    copy(rooms=
      (rooms.keys ++ willRoomsConf.keys).toList.distinct.sorted
        .foldLeft(rooms){ (st,roomKey)=>
          val wasOpt = rooms.get(roomKey)
          val willConfOpt = willRoomsConf.get(roomKey)
          if(wasOpt.map(_.conf)==willConfOpt) st else {
            for(was<-wasOpt){
              was.killSwitch.shutdown()
              println(s"shutdown $roomKey")
            }
            willConfOpt.fold(st - roomKey){ conf =>
              val killSwitch = KillSwitches.shared(s"room-$roomKey")
              val flow = createRoom(conf,killSwitch)
              println(s"created $roomKey")
              st + (roomKey->RoomAccess(conf,flow,killSwitch))
            }
          }
        }
    )

  def receive(
    rooms: Map[RoomKey,RoomConf], message: ToMainMessage
  ): Map[RoomKey,RoomConf] = message match {
    case RoomFlowReq(roomKey,resp) =>
      resp.success(rooms.get(roomKey).map(_.flow))
      rooms
    case RoomsConf(will) => configureRooms(rooms, will)
  }

  def run(): Unit = execution.fatal{ implicit ec =>
    for(mat <- akkaMat.get)
      yield MergeHub.source[ToMainMessage]
        .mapMaterializedValue(mainSink.success)
        .toMat(Sink.fold(Map.empty[RoomKey,RoomConf])(receive))(Keep.right)
        .run()(mat)
  }

  def wrapWebSocketFlow(inner: Flow[String,String,_]): Flow[Message,Message,_] =
    Flow[Message].mapAsync(1){
      case m: TextMessage =>
        execution.fatal { implicit ec =>
          for {
            mat <- akkaMat.get
            strictMessage <- m.toStrict(1.seconds)(mat)
          } yield strictMessage.text
        }
      case _: BinaryMessage => Future.successful("")
    }.via(inner).map(TextMessage(_))

  def handleWebSocket(req: HttpRequest): Option[Flow[String,String,_]=>HttpResponse] =
    req.attribute(AttributeKeys.webSocketUpgrade)
      .map(upgrade=>innerFlow=>upgrade.handleMessages(wrapWebSocketFlow(innerFlow)))

  def handle(req: HttpRequest): Future[HttpResponse] = handleWebSocket(req)
    .fold(Future.successful(HttpResponse(400, entity = ""))){handle=>
      val promise = Promise[Option[Flow[String, String, NotUsed]]]()
      val roomKey = req.uri.path.toString.split('/').last
      execution.fatal { implicit ec =>
        for {
          respOpt <- promise.future
        } yield respOpt.fold(HttpResponse(404, entity = ""))(handle)
        for {
          mat <- akkaMat.get
          sink <- mainSink.future
        } yield Source.single(RoomFlowReq(roomKey, promise)).to(sink).run()(mat) // failure will print warning
      }
    }
}