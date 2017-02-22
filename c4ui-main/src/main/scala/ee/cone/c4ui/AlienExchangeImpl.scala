package ee.cone.c4ui

import java.net.URL
import java.util.UUID

import ee.cone.c4actor.BranchTypes.BranchKey
import ee.cone.c4actor.LEvent.{add, delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, WorldKey, assemble}
import ee.cone.c4gate.AlienProtocol.{FromAlienState, ToAlienWrite}
import ee.cone.c4gate.HttpProtocol.HttpPost
import okio.ByteString

case object ToAlienPriorityKey extends WorldKey[java.lang.Long](0L)
object SendToAlienInit extends InitLocal {
  def initLocal: World ⇒ World = SendToAlienKey.set(
    (sessionKey,event,data) ⇒ local ⇒ {
      val id = UUID.randomUUID.toString
      val priority = ToAlienPriorityKey.of(local)
      add(update(ToAlienWrite(id,sessionKey,event,data,priority)))
        .andThen(ToAlienPriorityKey.modify(_+1))(local)
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
  def rm: World ⇒ World = add(delete(request))
}

@assemble class MessageFromAlienAssemble extends Assemble {
  def mapHttpPostByBranch(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(BranchKey, MessageFromAlien)] =
    for(post ← posts if post.path == "/connection") yield {
      val headers = post.headers.flatMap(h ⇒
        if(h.key.startsWith("X-r-")) Seq(h.key→h.value) else Nil
      ).toMap
      headers("X-r-branch") → MessageFromAlienImpl(post.srcId,headers("X-r-index").toLong,headers,post)
    }
}

@assemble class FromAlienBranchAssemble(operations: BranchOperations) extends Assemble {
  // more rich session may be joined
  def fromAliensToSeeds(
    key: SrcId,
    fromAliens: Values[FromAlienState]
  ): Values[(BranchKey, BranchRel)] =
  for(fromAlien ← fromAliens; child ← Option(operations.toSeed(fromAlien)))
    yield operations.toRel(child, fromAlien.sessionKey, parentIsSession = true)
}

@assemble class FromAlienTaskAssemble(host: String, file: String) extends Assemble {
  def mapBranchTaskByLocationHash(
    key: SrcId,
    tasks: Values[BranchTask]
  ): Values[(SrcId, FromAlienTask)] =
    for (
      task ← tasks;
      fromAlien ← Option(task.product).collect { case s: FromAlienState ⇒ s };
      url ← Option(new URL(fromAlien.location)) if url.getHost == host && url.getFile == file
    ) yield task.branchKey → FromAlienTask(task.branchKey, fromAlien, Option(url.getRef).getOrElse(""))
}
