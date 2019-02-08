
package ee.cone.c4actor

import PCProtocol.{RawChildNode, RawParentNode}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4actor.LEvent._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, _}

@protocol(TestCat) object PCProtocol extends Protocol {
  @Id(0x0003) case class RawChildNode(@Id(0x0003) srcId: String, @Id(0x0005) parentSrcId: String, @Id(0x0004) caption: String)
  @Id(0x0001) case class RawParentNode(@Id(0x0003) srcId: String, @Id(0x0004) caption: String)
}

case class ParentNodeWithChildren(srcId: String, caption: String, children: Values[RawChildNode])

@assemble class TestAssemble extends Assemble {
  type ParentSrcId = SrcId
  def joinChildNodeByParent(
    key: SrcId,
    child: Each[RawChildNode]
  ): Values[(ParentSrcId,RawChildNode)] = List(child.parentSrcId → child)

  def joinParentNodeWithChildren(
    key: SrcId,
    @by[ParentSrcId] childNodes: Values[RawChildNode],
    parent: Each[RawParentNode]
  ): Values[(SrcId,ParentNodeWithChildren)] =
    List(parent.srcId → ParentNodeWithChildren(parent.srcId, parent.caption, childNodes))
  /* todo:
  IO[SrcId,ParentNodeWithChildren](
    for(parent <- IO[SrcId,RawParentNode])
      yield ParentNodeWithChildren(parent.srcId, parent.caption, IO[ParentSrcId,RawChildNode](
        key => for(child <- IO[SrcId,RawChildNode]) yield child.parentSrcId → child
      ))
  )
  ////////

  Pairs[ParentSrcId,RawChildNode] =
    for(child <- Values[SrcId,RawChildNode]) yield child.parentSrcId → child

  Values[SrcId,ParentNodeWithChildren] =
    for(parent <- Values[SrcId,RawParentNode])
      yield ParentNodeWithChildren(parent.srcId, parent.caption, Values[ParentSrcId,RawChildNode])
  */

}

class AssemblerTestApp extends TestRichDataApp
  with TreeIndexValueMergerFactoryApp
  with SimpleAssembleProfilerApp
{
  override def protocols: List[Protocol] = PCProtocol :: super.protocols
  override def assembles: List[Assemble] = new TestAssemble :: super.assembles
}

object AssemblerTest extends App with LazyLogging {
  val app = new AssemblerTestApp
  val recs = update(RawParentNode("1","P-1")) ++
    List("2","3").flatMap(srcId ⇒ update(RawChildNode(srcId,"1",s"C-$srcId")))
  val updates = recs.map(rec⇒app.toUpdate.toUpdate(rec)).toList
  //println(app.qMessages.toTree(rawRecs))
  val nGlobal = app.contextFactory.updated(updates)
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
  logger.debug(s"$nGlobal")
  Map(
    ByPK(classOf[PCProtocol.RawParentNode]) -> Map(
      "1" -> RawParentNode("1","P-1")
    ),
    ByPK(classOf[PCProtocol.RawChildNode]) -> Map(
      "2" -> RawChildNode("2","1","C-2"),
      "3" -> RawChildNode("3","1","C-3")
    ),
    ByPK(classOf[ParentNodeWithChildren]) -> Map(
      "1" -> ParentNodeWithChildren("1",
        "P-1",
        List(RawChildNode("2","1","C-2"), RawChildNode("3","1","C-3"))
      )
    )
  ).foreach{
    case (k,v) ⇒ assert(k.of(nGlobal).toMap==v)
  }
}
