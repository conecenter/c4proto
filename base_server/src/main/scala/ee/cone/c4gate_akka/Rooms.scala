
package ee.cone.c4gate_akka

import ee.cone.c4actor.{Executable, Execution}

import java.nio.file.Paths
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

import akka.NotUsed
import akka.util.ByteString
import akka.stream.{KillSwitch, KillSwitches, SharedKillSwitch}
import akka.stream.scaladsl.{Flow, Keep, MergeHub, Sink, Source}

import akka.http.scaladsl.model.{HttpRequest, HttpResponse, ResponseEntity}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, UpgradeToWebSocket}

import ee.cone.c4gate_akka.Rooms._

trait RoomFactory {
  def createRoom(will: RoomConf, killSwitch: SharedKillSwitch): Flow[ByteString,ByteString,NotUsed]
}

object Rooms {
  sealed trait ToMainMessage

  case class RoomFlowReq(
    roomKey: RoomKey, response: Promise[Option[Flow[ByteString,ByteString,NotUsed]]]
  ) extends ToMainMessage

  case class RoomsConf(rooms: Map[RoomKey,RoomConf]) extends ToMainMessage

  type RoomKey = String
  type RoomConf = String

  class RoomAccess(
    val conf: RoomConf,
    val flow: Flow[ByteString,ByteString,NotUsed],
    val killSwitch: KillSwitch
  )
}

trait RoomsHandler {
  def handle(req: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse]
}

class RoomsManagerImpl(
  execution: Execution,
  akkaMat: AkkaMat,
  roomFactory: RoomFactory
)(
  val mainSink: Promise[Sink[ToMainMessage,NotUsed]] = Promise[Sink[ToMainMessage,NotUsed]]()
) extends RoomsHandler with Executable {

  def configureRooms(
    rooms: Map[RoomKey,RoomAccess], willRoomsConf: Map[RoomKey,RoomConf]
  ): Map[RoomKey,RoomAccess] =
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
            val flow = roomFactory.createRoom(conf,killSwitch)
            println(s"created $roomKey")
            st + (roomKey -> new RoomAccess(conf,flow,killSwitch))
          }
        }
      }

  private def ignoreFailureWillPrintWarning(nu: NotUsed): Unit = ()

  def receive(
    rooms: Map[RoomKey,RoomAccess], message: ToMainMessage
  ): Map[RoomKey,RoomAccess] = message match {
    case RoomFlowReq(roomKey,resp) =>
      execution.success(resp, rooms.get(roomKey).map(_.flow))
      rooms
    case RoomsConf(will) => configureRooms(rooms, will)
  }

  def run(): Unit = execution.fatal{ implicit ec =>
    for(mat <- akkaMat.get)
      yield MergeHub.source[ToMainMessage]
        .mapMaterializedValue(mainSink.success)
        .toMat(Sink.fold(Map.empty[RoomKey,RoomAccess])(receive _))(Keep.right)
        .run()(mat)
  }

  def getData(message: Message)(implicit ec: ExecutionContext): Future[ByteString] =
    for {
      mat <- akkaMat.get
      data <- message match {
        case m: BinaryMessage =>
          m.toStrict(1.seconds)(mat).map(_.data)
        case m: TextMessage =>
          m.toStrict(1.seconds)(mat).map(tm=>ByteString(tm.text))
      }
    } yield data

  def handleWebSocket(
    req: HttpRequest
  )(implicit ec: ExecutionContext): Option[Flow[ByteString,ByteString,_]=>HttpResponse] =
    req.header[UpgradeToWebSocket]
      .map(upgrade=>innerFlow=>upgrade.handleMessages(
        Flow[Message].mapAsync(1)(getData(_)).via(innerFlow).map(BinaryMessage(_))
      ))

  def handle(req: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse] = handleWebSocket(req)
    .fold(Future.successful(HttpResponse(400, entity = ""))){handle=>
      val promise = Promise[Option[Flow[ByteString, ByteString, NotUsed]]]()
      val roomKey = req.uri.path.toString.split('/').last
      for {
        mat <- akkaMat.get
        sink <- mainSink.future
        respOpt <- {
          ignoreFailureWillPrintWarning(Source.single(RoomFlowReq(roomKey, promise)).to(sink).run()(mat))
          promise.future
        }
      } yield respOpt.fold(HttpResponse(404, entity = ""))(handle)
    }
}