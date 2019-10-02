package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ConnProtocol.D_Node
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, c4component, protocol}

@protocol("ConnTestApp") object ConnProtocolBase   {
  @Id(0x0001) case class D_Node(@Id(0x0003) srcId: String, @Id(0x0005) parentId: String)
}

case class ConnNodePath(path: List[D_Node])

@assemble("ConnTestApp") class ConnAssembleBase   {
  type ParentId = SrcId

  def nodesByParentId(
      key: SrcId,
      node: Each[D_Node]
  ): Values[(ParentId,D_Node)] = List(node.parentId -> node)

  def connect(
      key: SrcId,
      @was paths: Values[ConnNodePath],
      @by[ParentId] node: Each[D_Node]
  ): Values[(SrcId,ConnNodePath)] = {
    for {
      path <- if(key.nonEmpty) paths else List(ConnNodePath(Nil))
    } yield {
      WithPK(path.copy(path=node::path.path))
    }
  }

  /*
  By[ParentId,D_Node] := for(node <- Is[D_Node] if node.parentId.nonEmpty) yield node.parentId -> node
  Is[List[D_Node]]    := for(node <- Is[D_Node] if node.parentId.isEmpty) yield WithPK(node::Nil)
  Is[List[D_Node]]    := WithPK(Each(By[ParentId,D_Node])::Each(Was[List[D_Node]]))
  */
}
@c4component("ConnTestApp") class ConnStart(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
  def run() = {
    import LEvent.update
    val recs = update(D_Node("1","")) ++
      update(D_Node("12","1")) ++ update(D_Node("13","1")) ++
      update(D_Node("124","12")) ++ update(D_Node("125","12"))
    val updates = recs.map(rec=>toUpdate.toUpdate(rec)).toList
    val nGlobal = contextFactory.updated(updates)

    //logger.info(s"${nGlobal.assembled}")
    assert(
      ByPK(classOf[ConnNodePath]).of(nGlobal)("125") ==
      ConnNodePath(List(
        D_Node("125","12"), D_Node("12","1"), D_Node("1","")
      ))
    )

    execution.complete()
    /*
    Map(
      ByPK(classOf[PCProtocol.D_RawParentNode]) -> Map(
        "1" -> D_RawParentNode("1","P-1")
      ),
      ByPK(classOf[PCProtocol.D_RawChildNode]) -> Map(
        "2" -> D_RawChildNode("2","1","C-2"),
        "3" -> D_RawChildNode("3","1","C-3")
      ),
      ByPK(classOf[ParentNodeWithChildren]) -> Map(
        "1" -> ParentNodeWithChildren("1",
          "P-1",
          List(D_RawChildNode("2","1","C-2"), D_RawChildNode("3","1","C-3"))
        )
      )
    ).foreach{
      case (k,v) => assert(k.of(nGlobal).toMap==v)
    }*/
  }
}
