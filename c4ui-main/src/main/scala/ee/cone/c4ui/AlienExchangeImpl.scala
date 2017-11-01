package ee.cone.c4ui

import java.net.URL
import java.util.UUID

import ee.cone.c4actor.BranchTypes.BranchKey
import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.AlienProtocol.{FromAlienState, ToAlienWrite}
import ee.cone.c4gate.HttpProtocol.HttpPost
import ee.cone.c4gate.LocalPostConsumer
import okio.ByteString

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
  index: Long,
  headers: Map[String,String],
  request: HttpPost
) extends MessageFromAlien {
  def header: String ⇒ String = k ⇒ headers.getOrElse(k,"")
  def body: ByteString = request.body
  def rm: Context ⇒ Context = TxAdd(delete(request))
}

@assemble class MessageFromAlienAssemble extends Assemble {
  def mapHttpPostByBranch(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(BranchKey, MessageFromAlien)] = for(
    post ← posts if post.path == "/connection";
    headers ← Option(post.headers.flatMap(h ⇒
      if(h.key.startsWith("X-r-")) List(h.key→h.value) else Nil
    ).toMap);
    branchKey ← headers.get("X-r-branch");
    index ← headers.get("X-r-index").map(_.toLong)
  ) yield branchKey → MessageFromAlienImpl(post.srcId,index,headers,post)


  def consumersForHandlers(
    key: SrcId,
    handlers: Values[BranchHandler]
  ): Values[(SrcId,LocalPostConsumer)] =
    for(h ← handlers) yield WithPK(LocalPostConsumer(h.branchKey))

}

@c4component @listed @assemble case class FromAlienBranchAssemble(operations: BranchOperations) extends Assemble {
  // more rich session may be joined
  def fromAliensToSeeds(
    key: SrcId,
    fromAliens: Values[FromAlienState]
  ): Values[(BranchKey, BranchRel)] =
  for(fromAlien ← fromAliens; child ← Option(operations.toSeed(fromAlien)))
    yield operations.toRel(child, fromAlien.sessionKey, parentIsSession = true)
}

@assemble class FromAlienTaskAssemble(file: String) extends Assemble {
  def mapBranchTaskByLocationHash(
    key: SrcId,
    tasks: Values[BranchTask]
  ): Values[(SrcId, FromAlienTask)] =
    for (
      task ← tasks;
      fromAlien ← Option(task.product).collect { case s: FromAlienState ⇒ s };
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
