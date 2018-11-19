
package ee.cone.c4actor

import PCProtocol.{RawChildNode, RawParentNode}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4actor.LEvent._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, _}
import ee.cone.c4proto

@protocol object PCProtocol extends c4proto.Protocol {
  @Id(0x0003) case class RawChildNode(@Id(0x0003) srcId: String, @Id(0x0005) parentSrcId: String, @Id(0x0004) caption: String)
  @Id(0x0001) case class RawParentNode(@Id(0x0003) srcId: String, @Id(0x0004) caption: String)
}

case class ParentNodeWithChildren(srcId: String, caption: String, children: Values[RawChildNode])
class TestAssemble extends Assemble {
  type ParentSrcId = SrcId

  def joinChildNodeByParent(key: SrcId, child: Each[RawChildNode]): Values[(ParentSrcId, RawChildNode)] = List(child.parentSrcId → child)

  def joinParentNodeWithChildren(key: SrcId, @by[ParentSrcId]() childNodes: Values[RawChildNode], parent: Each[RawParentNode]): Values[(SrcId, ParentNodeWithChildren)] = List(parent.srcId → ParentNodeWithChildren(parent.srcId, parent.caption, childNodes))

  override def dataDependencies = indexFactory => List(indexFactory.createJoinMapIndex(new ee.cone.c4assemble.Join("TestAssemble", "joinChildNodeByParent", collection.immutable.Seq(indexFactory.util.joinKey(false, "SrcId", classOf[SrcId].getName, classOf[RawChildNode].getName)), indexFactory.util.joinKey(false, "ParentSrcId", classOf[ParentSrcId].getName, classOf[RawChildNode].getName), (indexRawSeqSeq, diffIndexRawSeq) => {
    val iUtil = indexFactory.util
    val Seq(child_diffIndex) = diffIndexRawSeq
    val invalidateKeySet = iUtil.invalidateKeySet(diffIndexRawSeq)
    val child_warn = "joinChildNodeByParent child " + classOf[RawChildNode].getName
    for (indexRawSeqI <- indexRawSeqSeq;
         (dir, indexRawSeq) = indexRawSeqI;
         Seq(child_index) = indexRawSeq;
         id <- invalidateKeySet(indexRawSeq);
         child_parts = iUtil.partition(child_index, child_diffIndex, id, child_warn);
         child_part <- child_parts;
         (child_isChanged, child_items) = child_part;
         pass <- if (child_isChanged) iUtil.nonEmptySeq else Nil;
         child_arg <- child_items();
         pair <- joinChildNodeByParent(id.asInstanceOf[SrcId], child_arg.asInstanceOf[Each[RawChildNode]])) yield {
      val (byKey, product) = pair
      iUtil.result(byKey, product, dir)
    }
  }
  )
  ), indexFactory.createJoinMapIndex(new ee.cone.c4assemble.Join("TestAssemble", "joinParentNodeWithChildren", collection.immutable.Seq(indexFactory.util.joinKey(false, "ParentSrcId", classOf[ParentSrcId].getName, classOf[RawChildNode].getName), indexFactory.util.joinKey(false, "SrcId", classOf[SrcId].getName, classOf[RawParentNode].getName)), indexFactory.util.joinKey(false, "SrcId", classOf[SrcId].getName, classOf[ParentNodeWithChildren].getName), (indexRawSeqSeq, diffIndexRawSeq) => {
    val iUtil = indexFactory.util
    val Seq(childNodes_diffIndex, parent_diffIndex) = diffIndexRawSeq
    val invalidateKeySet = iUtil.invalidateKeySet(diffIndexRawSeq)
    val childNodes_warn = "joinParentNodeWithChildren childNodes " + classOf[RawChildNode].getName
    val parent_warn = "joinParentNodeWithChildren parent " + classOf[RawParentNode].getName
    for (
      indexRawSeqI <- indexRawSeqSeq;
      (dir, indexRawSeq) = indexRawSeqI;
      Seq(childNodes_index, parent_index) = indexRawSeq;
      id <- invalidateKeySet(indexRawSeq);
      childNodes_arg = iUtil.getValues(childNodes_index, id, childNodes_warn);
      childNodes_isChanged = iUtil.nonEmpty(childNodes_diffIndex, id);
      parent_parts = iUtil.partition(parent_index, parent_diffIndex, id, parent_warn);
      parent_part <- parent_parts;
      (parent_isChanged, parent_items) = parent_part;
      pass <- if (childNodes_isChanged || parent_isChanged) iUtil.nonEmptySeq else Nil;
      parent_arg <- parent_items();
      pair <- joinParentNodeWithChildren(id.asInstanceOf[SrcId], childNodes_arg.asInstanceOf[Values[RawChildNode]], parent_arg.asInstanceOf[Each[RawParentNode]])) yield {
      val (byKey, product) = pair
      iUtil.result(byKey, product, dir)
    }
  }
  )
  )
  )
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
