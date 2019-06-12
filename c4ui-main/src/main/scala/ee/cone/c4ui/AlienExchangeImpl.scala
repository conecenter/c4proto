package ee.cone.c4ui

import java.net.URL
import java.util.UUID

import ee.cone.c4actor.BranchTypes.BranchKey
import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.AlienProtocol.{FromAlienState, ToAlienWrite}
import ee.cone.c4gate.HttpProtocol.S_HttpPost
import ee.cone.c4gate.LocalPostConsumer
import okio.ByteString

import scala.collection.immutable.Seq

case object ToAlienPriorityKey extends TransientLens[java.lang.Long](0L)
object SendToAlienInit extends ToInject {
  def toInject: List[Injectable] = SendToAlienKey.set(
    (sessionKeys,event,data) ⇒ local ⇒ if(sessionKeys.isEmpty) local else {
      val priority = ToAlienPriorityKey.of(local)
      val messages = sessionKeys.zipWithIndex.flatMap{
        case (sessionKey,i) ⇒
          val id = UUID.randomUUID.toString
          update(ToAlienWrite(id,sessionKey,event,data,priority+i))
      }
      //println(s"messages: $messages")
      ToAlienPriorityKey.modify(_+sessionKeys.size).andThen(TxAdd(messages))(local)
    }
  )
}

case class MessageFromAlienImpl(
  srcId: String,
  headers: Map[String,String],
  request: S_HttpPost
) extends BranchMessage {
  def header: String ⇒ String = k ⇒ headers.getOrElse(k,"")
  def body: ByteString = request.body
  def deletes: Seq[LEvent[Product]] = delete(request)
}

@assemble class MessageFromAlienAssembleBase   {
  def mapHttpPostByBranch(
    key: SrcId,
    post: Each[S_HttpPost]
  ): Values[(BranchKey, BranchMessage)] = if(post.path != "/connection") Nil else for(
    headers ← List(post.headers.flatMap(h ⇒
      if(h.key.startsWith("X-r-")) List(h.key→h.value) else Nil
    ).toMap);
    branchKey ← headers.get("X-r-branch");
    index ← headers.get("X-r-index").map(_.toLong)
  ) yield branchKey → MessageFromAlienImpl(post.srcId,headers,post)


  def consumersForHandlers(
    key: SrcId,
    h: Each[BranchHandler]
  ): Values[(SrcId,LocalPostConsumer)] =
    List(WithPK(LocalPostConsumer(h.branchKey)))

}

@assemble class FromAlienBranchAssembleBase(operations: BranchOperations)   {
  // more rich session may be joined
  def fromAliensToSeeds(
    key: SrcId,
    fromAlien: Each[FromAlienState]
  ): Values[(BranchKey, BranchRel)] = {
    val child = operations.toSeed(fromAlien)
    List(operations.toRel(child, fromAlien.sessionKey, parentIsSession = true))
  }
}

@assemble class FromAlienTaskAssembleBase(file: String)   {
  def mapBranchTaskByLocationHash(
    key: SrcId,
    task: Each[BranchTask]
  ): Values[(SrcId, FromAlienTask)] =
    for (
      fromAlien ← List(task.product).collect { case s: FromAlienState ⇒ s };
      url ← Option(new URL(fromAlien.location))
        if /*url.getHost == host && (*/ url.getFile == file || url.getPath == file
    ) yield task.branchKey → FromAlienTask(
      task.branchKey,
      task,
      fromAlien,
      Option(url.getQuery).getOrElse(""),
      Option(url.getRef).getOrElse("")
    )
}
