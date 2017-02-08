
package ee.cone.c4actor

import PCProtocol.{RawChildNode, RawParentNode}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4actor.LEvent._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, _}
import ee.cone.c4proto

@protocol object PCProtocol extends c4proto.Protocol {
  @Id(0x0003) case class RawChildNode(@Id(0x0003) srcId: String, @Id(0x0005) parentSrcId: String, @Id(0x0004) caption: String)
  @Id(0x0001) case class RawParentNode(@Id(0x0003) srcId: String, @Id(0x0004) caption: String)
}

case class ParentNodeWithChildren(srcId: String, caption: String, children: List[RawChildNode])
@assemble class TestAssemble extends Assemble {
  type ParentSrcId = SrcId
  def joinChildNodeByParent(
    key: SrcId,
    rawChildNode: Values[RawChildNode]
  ): Values[(ParentSrcId,RawChildNode)] =
    rawChildNode.map(child ⇒ child.parentSrcId → child)
  def joinParentNodeWithChildren(
    key: SrcId,
    @by[ParentSrcId] childNodes: Values[RawChildNode],
    rawParentNode: Values[RawParentNode]
  ): Values[(SrcId,ParentNodeWithChildren)] =
    rawParentNode.map(parent ⇒
      parent.srcId → ParentNodeWithChildren(parent.srcId, parent.caption, childNodes)
    )
  /* todo:
  IO[SrcId,ParentNodeWithChildren](
    for(parent <- IO[SrcId,RawParentNode])
      yield ParentNodeWithChildren(parent.srcId, parent.caption, IO[ParentSrcId,RawChildNode](
        key => for(child <- IO[SrcId,RawChildNode]) yield child.parentSrcId → child
      ))
  )
  */

}

class AssemblerTestApp extends ServerApp with ToStartApp {
  def rawQSender: RawQSender =
    new RawQSender { def send(rec: QRecord): Long = 0 }
  override def protocols: List[Protocol] = PCProtocol :: super.protocols
  override def assembles: List[Assemble] = new TestAssemble :: super.assembles
}

object AssemblerTest extends App {
  val indexFactory = new IndexFactoryImpl
  val app = new AssemblerTestApp
  val recs = update(RawParentNode("1","P-1")) ++
    List("2","3").flatMap(srcId ⇒ update(RawChildNode(srcId,"1",s"C-$srcId")))
  val rawRecs = recs.map(rec⇒app.qMessages.toRecord(NoTopicName,app.qMessages.toUpdate(rec))).toList
  //println(app.qMessages.toTree(rawRecs))
  val emptyWorld = app.qReducer.createWorld(Map())
  val world = app.qReducer.reduceRecover(emptyWorld, rawRecs)
  /*
  val shouldDiff = Map(
    By.srcId(classOf[PCProtocol.RawParentNode]) -> Map(
      "1" -> List(RawParentNode("1","P-1"))
    ),
    By.srcId(classOf[PCProtocol.RawChildNode]) -> Map(
      "2" -> List(RawChildNode("2","1","C-2")),
      "3" -> List(RawChildNode("3","1","C-3"))
    )
  )
  assert(diff==shouldDiff)*/
  println(world)
  Map(
    By.srcId(classOf[PCProtocol.RawParentNode]) -> Map(
      "1" -> List(RawParentNode("1","P-1"))
    ),
    By.srcId(classOf[PCProtocol.RawChildNode]) -> Map(
      "2" -> List(RawChildNode("2","1","C-2")),
      "3" -> List(RawChildNode("3","1","C-3"))
    ),
    By.srcId(classOf[ParentNodeWithChildren]) -> Map(
      "1" -> List(ParentNodeWithChildren("1",
        "P-1",
        List(RawChildNode("2","1","C-2"), RawChildNode("3","1","C-3"))
      ))
    )
  ).foreach{
    case (k,v) ⇒ assert(world(k)==v)
  }
}
