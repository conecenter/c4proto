package ee.cone.c4ui

import java.net.URL
import java.util.UUID
import ee.cone.c4actor_branch.BranchTypes.BranchKey
import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor_branch._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, c4assemble}
import ee.cone.c4gate.AlienProtocol.{U_FromAlienConnected, U_FromAlienState, U_ToAlienWrite}
import ee.cone.c4gate.HttpProtocol.S_HttpRequest
import ee.cone.c4gate.LocalHttpConsumer
import ee.cone.c4di.c4
import okio.ByteString
import scala.collection.immutable.Seq

case object ToAlienPriorityKey extends TransientLens[java.lang.Long](0L)

@c4("AlienExchangeCompApp") final class ToAlienSenderImpl(
  txAdd: LTxAdd,
  getU_FromAlienConnected: GetByPK[U_FromAlienConnected],
) extends ToAlienSender {
  def send(sessionKeys: Seq[String], evType: String, data: String): Context => Context = local =>
    if(sessionKeys.isEmpty) local else doSend(sessionKeys, evType, data, local)
  private def doSend(sessionKeys: Seq[String], evType: String, data: String, local: Context): Context = {
    val priority = ToAlienPriorityKey.of(local)
    val messages = sessionKeys.zipWithIndex.flatMap{
      case (sessionKey,i) =>
        val id = UUID.randomUUID.toString
        update(U_ToAlienWrite(id,sessionKey,evType,data,priority+i))
    }
    //println(s"messages: $messages")
    ToAlienPriorityKey.modify(_+sessionKeys.size).andThen(txAdd.add(messages))(local)
  }
  def getConnectionKey(sessionKey: String, local: Context): Option[String] =
    getU_FromAlienConnected.ofA(local).get(sessionKey).map(_.connectionKey)
}

case class MessageFromAlienImpl(
  srcId: String,
  headers: Map[String,String],
  request: S_HttpRequest
) extends BranchMessage {
  def method: String = request.method match { case "" => "POST" case m => m }
  def header: String => String = k => headers.getOrElse(k,"")
  def body: ByteString = request.body
  def deletes: Seq[LEvent[Product]] = delete(request)
}

@c4assemble("AlienExchangeCompApp") class MessageFromAlienAssembleBase   {
  def mapHttpReqByBranch(
    key: SrcId,
    req: Each[S_HttpRequest]
  ): Values[(BranchKey, BranchMessage)] = if(req.path != "/connection") Nil else for(
    headers <- List(req.headers.flatMap(h =>
      if(h.key.startsWith("x-r-")) List(h.key->h.value) else Nil
    ).toMap);
    branchKey <- headers.get("x-r-branch");
    index <- headers.get("x-r-index").map(_.toLong)
  ) yield branchKey -> MessageFromAlienImpl(req.srcId,headers,req)


  def consumersForHandlers(
    key: SrcId,
    h: Each[BranchHandler]
  ): Values[(SrcId,LocalHttpConsumer)] =
    List(WithPK(LocalHttpConsumer(h.branchKey)))

}

@c4assemble("AlienExchangeCompApp") class FromAlienBranchAssembleBase(operations: BranchOperations)   {
  // more rich session may be joined
  def fromAliensToSeeds(
    key: SrcId,
    fromAlien: Each[U_FromAlienState]
  ): Values[(BranchKey, BranchRel)] = {
    val child = operations.toSeed(fromAlien)
    List(operations.toRel(child, fromAlien.sessionKey, parentIsSession = true))
  }
}

@assemble class FromAlienTaskAssembleBase(file: String) {
  def mapBranchTaskByLocationHash(
    key: SrcId,
    task: Each[BranchTask]
  ): Values[(SrcId, FromAlienTask)] =
    for (
      fromAlien <- List(task.product).collect { case s: U_FromAlienState => s };
      url <- Option(new URL(fromAlien.location))
        if /*url.getHost == host && (*/ url.getFile == file || url.getPath == file
    ) yield task.branchKey -> FromAlienTask(
      task.branchKey,
      task,
      fromAlien,
      Option(url.getQuery).getOrElse(""),
      Option(url.getRef).getOrElse("")
    )
}

