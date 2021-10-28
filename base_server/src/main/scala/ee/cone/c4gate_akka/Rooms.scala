
package ee.cone.c4gate_akka

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.{Executable, Execution}

import java.nio.file.Paths
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

import akka.NotUsed
import akka.util.ByteString
import akka.stream.{KillSwitch, KillSwitches, SharedKillSwitch}
import akka.stream.scaladsl.{Flow, Keep, MergeHub, Sink, Source}

import akka.http.scaladsl.model.{HttpRequest, HttpResponse, ResponseEntity}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, UpgradeToWebSocket}

import ee.cone.c4gate_akka.Rooms._

object Rooms {
  sealed trait ToMainMessage

  case class RoomFlowReq(
    path: RoomPath, response: Promise[Option[Flow[ByteString,ByteString,NotUsed]]]
  ) extends ToMainMessage

  case class RoomsConf(rooms: Map[RoomPath,RoomConf]) extends ToMainMessage

  type RoomPath = String
  type RoomConf = String

  class RoomAccess(
    val conf: RoomConf,
    val flow: Flow[ByteString,ByteString,NotUsed],
    val killSwitch: KillSwitch
  )
}

@c4("AkkaGatewayApp") final class RoomsManager(
  execution: Execution, akkaMat: AkkaMat,
  roomFactoryList: List[RoomFactory],
)(
  mainSinkPromise: Promise[Sink[ToMainMessage,NotUsed]] = Promise()
)(
  mainSink: Sink[ToMainMessage,Future[NotUsed]] =
    Sink.futureSink(mainSinkPromise.future)
) extends Executable {
  def configureRooms(
    rooms: Map[RoomPath,RoomAccess], willRoomsConf: Map[RoomPath,RoomConf]
  ): Map[RoomPath,RoomAccess] =
    (rooms.keys ++ willRoomsConf.keys).toList.distinct.sorted
      .foldLeft(rooms){ (st,path)=>
        val wasOpt = rooms.get(path)
        val willConfOpt = willRoomsConf.get(path)
        if(wasOpt.map(_.conf)==willConfOpt) st else {
          for(was<-wasOpt){
            was.killSwitch.shutdown()
            println(s"shutdown $path")
          }
          willConfOpt.fold(st - path){ conf =>
            val killSwitch = KillSwitches.shared(s"room-$path")
            val roomFactory = Single(roomFactoryList.filter(path.startsWith))
            val flow = roomFactory.createRoom(conf,killSwitch)
            println(s"created $path")
            st + (path -> new RoomAccess(conf,flow,killSwitch))
          }
        }
      }



  def receive(
    rooms: Map[RoomPath,RoomAccess], message: ToMainMessage
  ): Map[RoomPath,RoomAccess] = message match {
    case RoomFlowReq(path,resp) =>
      execution.success(resp, rooms.get(path).map(_.flow))
      rooms
    case RoomsConf(will) => configureRooms(rooms, will)
  }

  def run(): Unit = execution.fatal{ implicit ec =>
    for(mat <- akkaMat.get)
      yield MergeHub.source[ToMainMessage]
        .mapMaterializedValue(mainSinkPromise.success)
        .toMat(Sink.fold(Map.empty[RoomPath,RoomAccess])(receive _))(Keep.right)
        .run()(mat)
  }

  private def ignoreNotUsed(nu: NotUsed): Unit = () //?FailureWillPrintWarning
  def send(message: ToMainMessage): Unit =
    ignoreNotUsed(Source.single(message).to(mainSink).run()(mat))
}

@c4("AkkaGatewayApp") final class RoomsRequestHandlerProvider(
  roomFactoryList: List[RoomFactory],
  roomsRequestHandlerFactory: RoomsRequestHandlerFactory,
){
  @provide def handlers: Seq[AkkaRequestHandler] =
    roomFactoryList.map(f=>roomsRequestHandlerFactory.create(f.pathPrefix))
}

@c4multi("AkkaGatewayApp") final class RoomsRequestHandler(val pathPrefix: String)(
  roomsManager: RoomsManager, akkaMat: AkkaMat
) extends AkkaRequestHandler {
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

  def handleAsync(req: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse] =
    handleWebSocket(req)
    .fold(Future.successful(HttpResponse(400, entity = ""))){handle=>
      val promise = Promise[Option[Flow[ByteString, ByteString, NotUsed]]]()
      val path = req.uri.path.toString
      for {
        mat <- akkaMat.get
        respOpt <- {
          roomsManager.send(RoomFlowReq(path, promise))
          promise.future
        }
      } yield respOpt.fold(HttpResponse(404, entity = ""))(handle)
    }
}

@c4assemble("AkkaGatewayApp") class RoomsConfAssembleBase(
  actorName: ActorName,
  roomConfTxFactory: RoomConfTxFactory,
) {
  type RoomTxKey = SrcId
  def fromConf(
    srcId: SrcId,
    conf: Each[S_RoomsConf]
  ): Values[(RoomTxKey, S_RoomsConf)] =
    List(actorName -> conf)

  def toTx(
    srcId: SrcId,
    firstborn: Each[S_Firstborn]
    @by[RoomTxKey] confList: Values[S_RoomsConf]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(roomConfTxFactory.create("RoomConfTx",confList.toList.sorted(_.srcId))))
    ++ { path =>
      val key = "RoomFileConfTx"
      List(WithPK(RoomFileConfTx(key,Single.option(confList.filter(_.srcId==key)))))
    }
}

case object RoomConfWas extends TransientLens[List[S_RoomsConf]](Nil)
@c4multi("AkkaGatewayApp") final case class RoomConfTx(
  srcId: SrcId, confList: List[S_RoomsConf]
)(
  roomsManager: RoomsManager
) extends TxTransform {
  def transform(local: Context): Context = {
    if(RoomConfWas.of(local) != conf) {
      val confPairs = for {
        confs <- confList
        conf <- confs.rooms
      } yield conf.path -> conf.content
      roomsManager.send(RoomsConf(confPairs.toMap))
    }
    RoomConfWas.set(conf)(local)
  }
}

case object RoomConfFileContentWas extends TransientLens[String]("")
@c4multi("AkkaGatewayApp") final case class RoomFileConfTx(
  srcId: SrcId, confOpt: Option[S_RoomsConf]
)(
  config: ListConfig
) extends TxTransform {
  def transform(local: Context): Context = {
    val content = Single.option(config.get("C4ROOMS_CONF"))
      .map(Paths.get(_)).filter(Files.exists(_))
      .fold("")(path=>new String(Files.readAllBytes(path),UTF_8))
    if(RoomConfFileContentWas.get(local) != content){
      val ConfLineIgnore = """(\s*|#.*)""".r
      val ConfLineValues = """\s*(\S+)\s+(.*)""".r
      val rooms = content.split('\n').toSeq.flatMap {
        case ConfLineIgnore(_) => Nil
        case ConfLineValues(path, content) => List(N_RoomConf(path, content))
      }
      ??? S_RoomsConf(srcId,rooms)
      //parse
      // == confOpt
    }
    Function.chain(Seq(
      RoomConfFileContentWas.set(content),
      SleepUntilKey.set(now.plusSeconds(3)),
    ))(local)
  }
}
/*  def parseCamConf(content: String): RoomsConf = {
    val ConfLineIgnore = """(\s*|#.*)""".r
    val ConfLineValues = """\s*(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s*""".r
    RoomsConf(content.split('\n').toSeq.flatMap{
      case ConfLineIgnore(_) => Nil
      case ConfLineValues(roomKey,host,uri,username,password) =>
        List(roomKey -> RoomConf(host,uri,username,password))
    }.toMap)
  }*/