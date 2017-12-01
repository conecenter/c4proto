package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ConnProtocol.Node
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4proto._

@protocol object ConnProtocol {
  @Id(0x0001) case class Node(@Id(0x0003) srcId: String, @Id(0x0005) parentId: String)
}

@assemble class ConnAssemble {
  type ParentId = SrcId

  def nodesByParentId(
      key: SrcId,
      nodes: Values[Node]
  ): Values[(ParentId,Node)] = for {
      node ← nodes if node.parentId.nonEmpty
  } yield node.parentId → node

  def init(
    key: SrcId,
    nodes: Values[Node]
  ): Values[(SrcId,List[Node])] = for {
    node ← nodes if node.parentId.isEmpty
  } yield WithPK(node::Nil)

  def connect(
      key: SrcId,
      @was paths: Values[List[Node]],
      @by[ParentId] childNodes: Values[Node]
  ): Values[(SrcId,List[Node])] = for {
      path ← paths
      node ← childNodes
  } yield WithPK(node::path)


  /*
  By[ParentId,Node] := for(node ← Is[Node] if node.parentId.nonEmpty) yield node.parentId → node
  Is[List[Node]]    := for(node ← Is[Node] if node.parentId.isEmpty) yield WithPK(node::Nil)
  Is[List[Node]]    := WithPK(Each(By[ParentId,Node])::Each(Was[List[Node]]))
  */
}

@c4component @listed case class ConnStart(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
  def run() = {
    import LEvent.update
    val recs = update(Node("1","")) ++
      update(Node("12","1")) ++ update(Node("13","1")) ++
      update(Node("124","12")) ++ update(Node("125","12"))
    val updates = recs.map(rec⇒toUpdate.toUpdate(rec)).toList
    val context = contextFactory.create()
    val nGlobal = ReadModelAddKey.of(context)(updates)(context)

    logger.info(s"${nGlobal.assembled}")
    execution.complete()
    /*
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
    }*/
  }
}

class ConnTestApp extends RichDataApp
  with CompoundExecutableApp
  with `The VMExecution`
  with TreeIndexValueMergerFactoryApp
  with `The SimpleAssembleProfiler`
  with `The ConnProtocol`
  with `The ConnAssemble`
  with `The ConnStart`
