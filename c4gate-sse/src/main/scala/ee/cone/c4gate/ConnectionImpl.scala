package ee.cone.c4gate

import ee.cone.c4actor.Types.World
import ee.cone.c4actor.{Delete, MessageMapResult, MessageMapper, Update}
import ee.cone.c4gate.InternetProtocol.TcpStatus

object TcpStatusMapper extends MessageMapper(classOf[TcpStatus]){
  def mapMessage(world: World, message: TcpStatus): Seq[MessageMapResult] = {
    val srcId = message.connectionKey
    if(message.error.isEmpty) Seq(Update(srcId, TcpStatus(srcId,"")))
    else {
      val body = okio.ByteString.encodeUtf8(size)
      Seq(Delete(srcId, classOf[TcpStatus]))
    }




  }
}

//q-poll -> FromAlienDictMessage
//ShowToAlien -> sendToAlien
// /connection X-r-connection -> q-add

//(World,Msg) => (WorldWithChanges,Seq[Send])