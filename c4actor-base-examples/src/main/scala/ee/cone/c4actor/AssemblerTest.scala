
package ee.cone.c4actor

import PCProtocol.{RawChildNode,RawParentNode}
import ee.cone.c4actor.Types.{Index, SrcId, Values}
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object PCProtocol extends Protocol {
  @Id(0x0003) case class RawChildNode(@Id(0x0003) srcId: String, @Id(0x0005) parentSrcId: String, @Id(0x0004) caption: String)
  @Id(0x0001) case class RawParentNode(@Id(0x0003) srcId: String, @Id(0x0004) caption: String)
}

case object ChildNodeByParent extends WorldKey[Index[SrcId,RawChildNode]](Map.empty)
case class ParentNodeWithChildren(caption: String, children: List[RawChildNode])

class ChildNodeByParentJoin extends Join1(
  By.srcId(classOf[RawChildNode]), ChildNodeByParent
) {
  def join(rawChildNode: Values[RawChildNode]): Values[(SrcId,RawChildNode)] =
    rawChildNode.map(child ⇒ child.parentSrcId → child)
  def sort(nodes: Iterable[RawChildNode]): List[RawChildNode] =
    nodes.toList.sortBy(_.srcId)
}

class ParentNodeWithChildrenJoin extends Join2(
  ChildNodeByParent, By.srcId(classOf[RawParentNode]), By.srcId(classOf[ParentNodeWithChildren])
) {
  def join(
    childNodeByParent: Values[RawChildNode],
    rawParentNode: Values[RawParentNode]
  ): Values[(SrcId,ParentNodeWithChildren)] = {
    rawParentNode.map(parent ⇒
      parent.srcId → ParentNodeWithChildren(parent.caption, childNodeByParent)
    )
  }
  def sort(nodes: Iterable[ParentNodeWithChildren]): List[ParentNodeWithChildren] =
    if(nodes.size <= 1) nodes.toList else throw new Exception("PK")
}

class AssemblerTestApp extends QMessagesApp with TreeAssemblerApp {
  def rawQSender: RawQSender =
    new RawQSender { def send(rec: QRecord): Unit = () }
  override def protocols: List[Protocol] = PCProtocol :: super.protocols
  override def dataDependencies: List[DataDependencyTo[_]] =
    indexFactory.createJoinMapIndex(new ChildNodeByParentJoin) ::
    indexFactory.createJoinMapIndex(new ParentNodeWithChildrenJoin) ::
    super.dataDependencies
}

object AssemblerTest extends App {
  val indexFactory = new IndexFactoryImpl
  val app = new AssemblerTestApp
  val testActorName = ActorName("")
  var recs = Update("1", RawParentNode("1","P-1")) ::
    List("2","3").map(srcId ⇒ Update(srcId, RawChildNode(srcId,"1",s"C-$srcId")))

  val diff: Map[WorldKey[_], Index[Object, Object]] =
    app.qMessages.toTree(recs.map(app.qMessages.toRecord(Some(testActorName),_)))
  val world = app.treeAssembler.replace(Map.empty,diff)
  val shouldDiff = Map(
    By.srcId(classOf[PCProtocol.RawParentNode]) -> Map(
      "1" -> List(RawParentNode("1","P-1"))
    ),
    By.srcId(classOf[PCProtocol.RawChildNode]) -> Map(
      "2" -> List(RawChildNode("2","1","C-2")),
      "3" -> List(RawChildNode("3","1","C-3"))
    )
  )
  println(world)
  assert(diff==shouldDiff)
  assert(world==Map(
    By.srcId(classOf[PCProtocol.RawParentNode]) -> Map(
      "1" -> List(RawParentNode("1","P-1"))
    ),
    By.srcId(classOf[PCProtocol.RawChildNode]) -> Map(
      "2" -> List(RawChildNode("2","1","C-2")),
      "3" -> List(RawChildNode("3","1","C-3"))
    ),
    ChildNodeByParent -> Map(
      "1" -> List(RawChildNode("2","1","C-2"), RawChildNode("3","1","C-3"))
    ),
    By.srcId(classOf[ParentNodeWithChildren]) -> Map(
      "1" -> List(ParentNodeWithChildren(
        "P-1",
        List(RawChildNode("2","1","C-2"), RawChildNode("3","1","C-3"))
      ))
    )
  ))
}
