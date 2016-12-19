package ee.cone.c4gate

import ee.cone.c4actor.Types.{Values, World}
import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol.{HttpRequestTask, HttpRequestValue, TcpStatus, TcpWrite}

class SSETcpStatusMapper(
  sseMessages: SSEMessages
) extends MessageMapper(classOf[TcpStatus]){
  def mapMessage(res: MessageMapping, message: LEvent[TcpStatus]): MessageMapping = {
    if(!changing(mClass,res,message)) res
    val toSend = message.value.map(_⇒sseMessages.header(message.srcId)).toSeq
    res.add(toSend:_*).add(message)
  }
  def changing[M](cl: Class[M], res: MessageMapping, message: LEvent[M]): Boolean =
    message.value.toList == By.srcId(cl).of(res.world).getOrElse(message.srcId,Nil).toList
}

class SSEMessages(gateActorName: ActorName, allowOriginOption: Option[String]) {
  def header(connectionKey: String): LEvent[TcpWrite] = {
    val allowOrigin =
      allowOriginOption.map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
    val headerString = s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$allowOrigin\n"
    message(connectionKey, headerString)
  }
  def message(connectionKey: String, event: String, data: String): LEvent[TcpWrite] = {
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    message(connectionKey, s"event: $event\ndata: $escapedData\n\n")
  }
  private def message(connectionKey: String, data: String): LEvent[TcpWrite] = {
    val bytes = okio.ByteString.encodeUtf8(data)
    val msg = TcpWrite(connectionKey,bytes)
    LEvent.update(gateActorName, connectionKey, msg)
  }
}

class SSEHttpRequestValueMapper() extends MessageMapper(classOf[HttpRequestValue]) {
  override def mapMessage(res: MessageMapping, message: LEvent[HttpRequestValue]): MessageMapping = {
    val task = HttpRequestTask(message.offset.get, message.value)
    res.add(LEvent.update(res.topicName, task.offset.toString, task))
  }
}

case class TaskExecutorState(from: Long, lastTx: Option[MessageMapping])

class SSEHttpRequestTaskExecutor(
  getWorld: ()⇒World, reducer: Reducer, actorName: ActorName
) extends Executable {
  def run(ctx: ExecutionContext) = {

    Iterator.iterate(TaskExecutorState(0,None)){ state ⇒
      val tx = reducer.createMessageMapping(actorName, getWorld())


      state.copy(lastTx = Some(tx))
    }.foreach(state⇒state.lastTx)

    ( /: Iterator.continually(getWorld())){ (state, world)⇒
      val tasks = By.srcId(classOf[HttpRequestTask]).of(world).values
        .flatten.toSeq.filter(_.offset>=state.from).sortBy(_.offset) //.map(_.request.get)
      //import InternetProtocol._
      val req = tasks.collectFirst{
        case HttpRequestTask(offset,request) if ⇒ _.path == "/connection"
      }
      if(req.isE)


      state
    }








    val headers = req.headers.flatMap(h ⇒
      if(h.key.startsWith("X-r-")) Seq(h.key→h.value) else Nil
    ).toMap
    val connectionKey = headers("X-r-connection")
    ???
  }
}


// /connection X-r-connection -> q-add -> q-poll -> FromAlienDictMessage
// (0/1-1) ShowToAlien -> sendToAlien

//(World,Msg) => (WorldWithChanges,Seq[Send])