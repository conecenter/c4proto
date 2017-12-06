package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ConnProtocol.Node
import ee.cone.c4actor.Types._
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
}

@c4component @listed case class ConnStart(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory,
  path: ByPK[List[Node]] @c4key
) extends Executable with LazyLogging {
  def run(): Unit = {
    import LEvent.update
    val recs = update(Node("1","")) ++
      update(Node("12","1")) ++ update(Node("13","1")) ++
      update(Node("124","12")) ++ update(Node("125","12"))
    val updates = recs.map(rec⇒toUpdate.toUpdate(rec)).toList
    val context = contextFactory.create()
    val nGlobal = ReadModelAddKey.of(context)(updates)(context)
    logger.debug(s"${nGlobal.assembled}")
    println(path.of(nGlobal)("125"))
    execution.complete()
  }
}

/*
By[ParentId,Node] := for(node ← Is[Node] if node.parentId.nonEmpty) yield node.parentId → node
Is[List[Node]]    := for(node ← Is[Node] if node.parentId.isEmpty) yield WithPK(node::Nil)
Is[List[Node]]    := WithPK(Each(By[ParentId,Node])::Each(Was[List[Node]]))
*/