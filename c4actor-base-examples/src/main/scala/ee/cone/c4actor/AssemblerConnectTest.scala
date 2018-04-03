package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ConnProtocol.Node
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object ConnProtocol extends Protocol {
  @Id(0x0001) case class Node(@Id(0x0003) srcId: String, @Id(0x0005) parentId: String)
}

@assemble class ConnAssemble extends Assemble {
  type ParentId = SrcId

  def nodesByParentId(
      key: SrcId,
      nodes: Values[Node]
  ): Values[(ParentId,Node)] = for {
      node ← nodes
  } yield node.parentId → node

  def connect(
      key: SrcId,
      @was paths: Values[List[Node]],
      @by[ParentId] childNodes: Values[Node]
  ): Values[(SrcId,List[Node])] = for {
      path ← if(key.nonEmpty) paths else List(Nil)
      node ← childNodes
  } yield WithPK(node::path)

  /*
  By[ParentId,Node] := for(node ← Is[Node] if node.parentId.nonEmpty) yield node.parentId → node
  Is[List[Node]]    := for(node ← Is[Node] if node.parentId.isEmpty) yield WithPK(node::Nil)
  Is[List[Node]]    := WithPK(Each(By[ParentId,Node])::Each(Was[List[Node]]))
  */
}

class ConnStart(
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
  with ExecutableApp
  with VMExecutionApp
  with TreeIndexValueMergerFactoryApp
  with SimpleAssembleProfilerApp
  with ToStartApp
{
  override def protocols: List[Protocol] = ConnProtocol :: super.protocols
  override def assembles: List[Assemble] = new ConnAssemble :: super.assembles
  override def toStart: List[Executable] = new ConnStart(execution,toUpdate,contextFactory) :: super.toStart
  override def assembleSeqOptimizer: AssembleSeqOptimizer = new ShortAssembleSeqOptimizer(backStageFactory,indexUpdater)
}

//C4STATE_TOPIC_PREFIX=ee.cone.c4actor.ConnTestApp sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'