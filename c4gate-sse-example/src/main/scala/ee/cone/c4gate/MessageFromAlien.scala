package ee.cone.c4gate

import java.net.URL

import ee.cone.c4actor.BranchTypes.BranchKey
import ee.cone.c4actor.LEvent.{add, delete}
import ee.cone.c4actor.{BranchOperations, BranchRel, BranchTask, MessageFromAlien}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.HttpProtocol.HttpPost

case class MessageFromAlienImpl(
  srcId: String,
  index: Long,
  headers: Map[String,String],
  request: HttpPost
) extends MessageFromAlien {
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

case class FromAlienTask(branchKey: SrcId, fromAlienState: FromAlienState, locationHash: String)

@assemble class FromAlienBranchAssemble(operations: BranchOperations, host: String, file: String) extends Assemble {
  // more rich session may be joined
  //todo reg
  def fromAliensToSeeds(
    key: SrcId,
    fromAliens: Values[FromAlienState]
  ): Values[(BranchKey, BranchRel)] =
  for (fromAlien ← fromAliens; child ← Option(operations.toSeed(fromAlien)))
    yield operations.toRel(child, fromAlien.sessionKey, parentIsSession = true)

  def mapBranchTaskByLocationHash(
    key: SrcId,
    tasks: Values[BranchTask]
  ): Values[(SrcId, FromAlienTask)] =
    for (
      task ← tasks;
      fromAlien ← Option(task.product).collect { case s: FromAlienState ⇒ s };
      url ← Option(new URL(fromAlien.location)) if url.getHost == host && url.getFile == file;
      ref ← Option(url.getRef)
    ) yield task.branchKey → FromAlienTask(task.branchKey, fromAlien, ref)
}
