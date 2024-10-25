package ee.cone.c4ui

import ee.cone.c4actor_branch.BranchTypes.BranchKey
import ee.cone.c4actor.LEvent.delete
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor_branch.BranchProtocol.U_Redraw
import ee.cone.c4actor_branch._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.HttpProtocol.S_HttpRequest
import ee.cone.c4gate.LocalHttpConsumer
import ee.cone.c4vdom.VDomMessage
import okio.ByteString

case class MessageFromAlienImpl(
  srcId: String,
  headers: Map[String,String],
  request: S_HttpRequest
) extends BranchMessage with VDomMessage {
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
    session: Each[U_AuthenticatedSession]
  ): Values[(SrcId,LocalHttpConsumer)] =
    List(WithPK(LocalHttpConsumer(session.logKey)))
}

@c4assemble("AlienExchangeCompApp") class RedrawAssembleBase {
  def redrawByBranch(
    key: SrcId,
    redraw: Each[U_Redraw]
  ): Values[(BranchKey, BranchMessage)] = List(redraw.branchKey -> RedrawBranchMessage(redraw))
}

case class RedrawBranchMessage(redraw: U_Redraw) extends BranchMessage with VDomMessage {
  def method: String = "POST"
  def header: String => String = { case "x-r-op" => "redraw" case _ => "" }
  def body: okio.ByteString = okio.ByteString.EMPTY
  def deletes: Seq[LEvent[Product]] = LEvent.delete(redraw)
}
