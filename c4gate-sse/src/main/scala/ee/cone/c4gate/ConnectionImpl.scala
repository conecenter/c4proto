package ee.cone.c4gate

import ee.cone.c4actor.Types.{Index, SrcId, World}
import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol.{TcpStatus, TcpWrite}

class SSETcpStatusMapper(
  gateActorName: ActorName, allowOriginOption: Option[String]
) extends MessageMapper(classOf[TcpStatus]){
  def mapMessage(
    res: MessageMapping,
    message: LEvent[TcpStatus]
  ): MessageMapping = {
    val srcId = message.srcId
    val connections: Index[SrcId, TcpStatus] =
      By.srcId(classOf[TcpStatus]).of(res.world)
    (if(message.value.nonEmpty && connections.contains(srcId)){
      val allowOrigin =
        allowOriginOption.map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
      val headerString = s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$allowOrigin\n"
      val headerMessage = TcpWrite(srcId,okio.ByteString.encodeUtf8(headerString))
      res.add(LEvent.update(gateActorName, message.srcId, headerMessage))
    } else res).add(message)
  }
}

//q-poll -> FromAlienDictMessage
//ShowToAlien -> sendToAlien
// /connection X-r-connection -> q-add

//(World,Msg) => (WorldWithChanges,Seq[Send])