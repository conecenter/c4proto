package ee.cone.c4actor.hashsearch.index

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import ee.cone.c4actor.HashSearch.Request
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.hashsearch.base.{HashSearchAssembleSharedKeys, InnerConditionEstimate, InnerLeaf}
import ee.cone.c4actor.hashsearch.index.HashSearchMockAssembleTest.K2TreeAll
import ee.cone.c4actor.hashsearch.rangers.K2TreeUtils._
import ee.cone.c4actor.hashsearch.rangers.RangeTreeProtocol.{S_K2TreeParams, S_TreeNode, S_TreeNodeOuter, S_TreeRange}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.ToByteString

object HashSearchMockUtils {
  def heapId(cl: Class[_], node: S_TreeNode, qAdapterRegistry: QAdapterRegistry, idGenUtil: IdGenUtil): SrcId = {
    val clName = cl.getName
    val valueAdapter = qAdapterRegistry.byName(node.getClass.getName)
    val bytes = ToByteString(valueAdapter.encode(node))
    idGenUtil.srcIdFromSerialized(valueAdapter.id, bytes)
  }

  def heapIds(cl: Class[_], nodes: List[S_TreeNode], qAdapterRegistry: QAdapterRegistry, idGenUtil: IdGenUtil): List[SrcId] =
    for {
      node ← nodes
    } yield heapId(cl, node, qAdapterRegistry, idGenUtil)

  def single[Something]: Values[Something] ⇒ Values[Something] =
    l ⇒ Single.option(l.distinct).toList

  def count[Model <: Product](heapSrcId: SrcId, respLines: Values[Model]): K2Count[Model] =
    K2Count(heapSrcId, Log2Pow2(respLines.length))

  def isMy[Model](cond: Condition[Model], name: NameMetaAttr): Boolean =
    cond match {
      case a: ProdCondition[_, Model] ⇒ a.metaList.collectFirst{case a:NameMetaAttr ⇒ a}.get == name
      case _ ⇒ false
    }
}

case class K2Need[Model <: Product](requestId: SrcId, modelCl: Class[Model])

case class K2Count[Model <: Product](heapSrcId: SrcId, count: Int)

object HashSearchMockAssembleTest {
  type K2TreeAll[Test] = All
}

import HashSearchMockUtils._

@assemble class HashSearchMockAssembleBase[Model <: Product](
  modelCl: Class[Model],
  getDate: Model ⇒ (Option[Long], Option[Long]),
  conditionToRegion: Condition[Model] ⇒ S_TreeRange,
  filterName: NameMetaAttr,
  qAdapterRegistry: QAdapterRegistry,
  idGenUtil: IdGenUtil
) extends   HashSearchAssembleSharedKeys{
  type K2HeapId = SrcId
  type K2ToCountId = SrcId


  def GetTreeToAll(
    treeId: SrcId,
    param: Each[S_K2TreeParams],
    tree: Each[S_TreeNodeOuter]
  ): Values[(K2TreeAll[Model], S_TreeNodeOuter)] =
    if(param.modelName == modelCl.getName) List(All → tree) else Nil

  // Index builder
  def RespLineByHeap(
    respLineId: SrcId,
    respLine: Each[Model],
    @by[K2TreeAll[Model]] tree: Each[S_TreeNodeOuter]
  ): Values[(K2HeapId, Model)] =
    for {
      tagId ← heapId(modelCl, findRegion(tree.root.get, getDate(respLine)), qAdapterRegistry, idGenUtil) :: Nil
    } yield tagId → respLine

  // end index builder

  // LeafRequest receive
  def ReqByHeap(
    leafCondId: SrcId,
    leaf: Each[InnerLeaf[Model]],
    @by[K2TreeAll[Model]] trees: Values[S_TreeNodeOuter]
  ): Values[(K2HeapId, K2Need[Model])] =
    if(isMy(leaf.condition, filterName))
      for {
        tree ← trees
        heapId ← heapIds(modelCl, getRegions(tree.root.get, conditionToRegion(leaf.condition)), qAdapterRegistry, idGenUtil)
      } yield heapId → K2Need[Model](ToPrimaryKey(leaf), modelCl)
    else Nil

  def RespHeapCountByReq(
    heapId: SrcId,
    @by[K2HeapId] respLines: Values[Model],
    @by[K2HeapId] need: Each[K2Need[Model]]
  ): Values[(K2ToCountId, K2Count[Model])] =
    List(need.requestId → count(heapId, respLines))

  // Count response
  def CountByReq(
    needId: K2HeapId,
    request: Each[InnerLeaf[Model]],
    @by[K2ToCountId] counts: Values[K2Count[Model]]
  ): Values[(SrcId, InnerConditionEstimate[Model])] =
    if(isMy(request.condition, filterName))
      List(WithPK(InnerConditionEstimate[Model](request.srcId, counts.map(_.count).sum, counts.toList.map(_.heapSrcId))))
    else Nil

  // Lines Response
  def RespByReq(
    heapId: SrcId,
    @by[K2HeapId] line: Each[Model],
    @by[SharedHeapId] request: Each[Request[Model]]
  ): Values[(SharedResponseId, Model)] = {
    //println(requests.toString().take(50), responses.size)
      if(request.condition.check(line)) List(ToPrimaryKey(request) → line)
      else Nil
  }

}
