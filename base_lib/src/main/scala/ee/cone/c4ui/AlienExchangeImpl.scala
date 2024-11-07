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
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpRequest}
import ee.cone.c4gate.LocalHttpConsumer
import ee.cone.c4vdom.VDomMessage
import okio.ByteString

case class MessageFromAlienImpl(
  orig: S_MessageFromAlien,
  headers: Map[String,String],
  body: ByteString,
) extends BranchMessage with VDomMessage {
  def header: String => String = k => headers.getOrElse(k,"")
  def deletes: Seq[LEvent[Product]] = delete(request)
}

case class S_MessageFromAlien(srcId: String, logKey: String, headers: List[N_Header])

@c4assemble("AlienExchangeCompApp") class MessageFromAlienAssembleBase   {
  def mapHttpReqByBranch(
    key: SrcId,
    req: Each[S_MessageFromAlien]
  ): Values[(BranchKey, BranchMessage)] = for(
    headers <- List(req.headers.flatMap(h =>
      if(h.key.startsWith("x-r-")) List(h.key->h.value) else Nil
    ).toMap);
    branchKey <- headers.get("x-r-branch);
    index <- headers.get("x-r-index").map(_.toLong)
  ) yield branchKey -> MessageFromAlienImpl(req.srcId,headers,req)
}
// MessageFromAlien: headers (reload index) branchKey

@c4assemble("AlienExchangeCompApp") class RedrawAssembleBase {
  def redrawByBranch(
    key: SrcId,
    redraw: Each[U_Redraw]
  ): Values[(BranchKey, BranchMessage)] = List(redraw.branchKey -> RedrawBranchMessage(redraw))
}

case class RedrawBranchMessage(redraw: U_Redraw) extends BranchMessage with VDomMessage {
  def header: String => String = { case "x-r-op" => "redraw" case _ => "" }
  def body: okio.ByteString = okio.ByteString.EMPTY
  def deletes: Seq[LEvent[Product]] = LEvent.delete(redraw)
}
