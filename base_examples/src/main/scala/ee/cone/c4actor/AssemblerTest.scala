
package ee.cone.c4actor

import PCProtocol.{D_RawChildNode, D_RawParentNode}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Id,protocol}
import ee.cone.c4actor.LEvent._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, _}
import ee.cone.c4di.c4

@protocol("AssemblerTestApp") object PCProtocol {
  @Id(0x0003) case class D_RawChildNode(@Id(0x0003) srcId: String, @Id(0x0005) parentSrcId: String, @Id(0x0004) caption: String)
  @Id(0x0001) case class D_RawParentNode(@Id(0x0003) srcId: String, @Id(0x0004) caption: String)
}

case class ParentNodeWithChildren(srcId: String, caption: String, children: Values[D_RawChildNode])

@c4assemble("AssemblerTestApp") class AssemblerTestAssembleBase   {
  type ParentSrcId = SrcId
  def joinChildNodeByParent(
    key: SrcId,
    child: Each[D_RawChildNode]
  ): Values[(ParentSrcId,D_RawChildNode)] = List(child.parentSrcId -> child)

  def joinParentNodeWithChildren(
    key: SrcId,
    @by[ParentSrcId] childNodes: Values[D_RawChildNode],
    parent: Each[D_RawParentNode]
  ): Values[(SrcId,ParentNodeWithChildren)] =
    List(parent.srcId -> ParentNodeWithChildren(parent.srcId, parent.caption, childNodes))
  /* todo:
  IO[SrcId,ParentNodeWithChildren](
    for(parent <- IO[SrcId,D_RawParentNode])
      yield ParentNodeWithChildren(parent.srcId, parent.caption, IO[ParentSrcId,D_RawChildNode](
        key => for(child <- IO[SrcId,D_RawChildNode]) yield child.parentSrcId -> child
      ))
  )
  ////////

  Pairs[ParentSrcId,D_RawChildNode] =
    for(child <- Values[SrcId,D_RawChildNode]) yield child.parentSrcId -> child

  Values[SrcId,ParentNodeWithChildren] =
    for(parent <- Values[SrcId,D_RawParentNode])
      yield ParentNodeWithChildren(parent.srcId, parent.caption, Values[ParentSrcId,D_RawChildNode])
  */

}

@c4("SimpleAssemblerTestApp") class AssemblerTest(
  toUpdate: ToUpdate,
  contextFactory: ContextFactory,
  execution: Execution
) extends Executable with LazyLogging {
  def run(): Unit = {

    val recs = update(D_RawParentNode("1","P-1")) ++
      List("2","3").flatMap(srcId => update(D_RawChildNode(srcId,"1",s"C-$srcId")))
    val updates = recs.map(rec=>toUpdate.toUpdate(rec)).toList
    //println(app.qMessages.toTree(rawRecs))
    val nGlobal = contextFactory.updated(updates)
    /*
    val shouldDiff = Map(
      By.srcId(classOf[PCProtocol.D_RawParentNode]) -> Map(
        "1" -> List(D_RawParentNode("1","P-1"))
      ),
      By.srcId(classOf[PCProtocol.D_RawChildNode]) -> Map(
        "2" -> List(D_RawChildNode("2","1","C-2")),
        "3" -> List(D_RawChildNode("3","1","C-3"))
      )
    )
    assert(diff==shouldDiff)*/
    logger.debug(s"$nGlobal")
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
    }
    execution.complete()
  }
}
