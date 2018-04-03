package ee.cone.c4actor.rangers

import java.nio.charset.StandardCharsets
import java.util.UUID

import ee.cone.c4actor.QAdapterRegistry
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.rangers.RangeTreeProtocol.{TreeNode, TreeNodeOuter, TreeRange}
import ee.cone.c4assemble.Types.Values
import StandardCharsets.UTF_8

import ee.cone.c4actor.HashSearch.Request
import ee.cone.c4actor.HashSearchImpl.{Need, Priority}
import ee.cone.c4assemble._

object HashSearchMockUtils {
  def heapId(cl: Class[_], node: TreeNode, qAdapterRegistry: QAdapterRegistry): SrcId = {
    val clName = cl.getName
    val valueAdapter = qAdapterRegistry.byName(node.getClass.getName)
    val bytes = valueAdapter.encode(node)
    UUID.nameUUIDFromBytes(clName.getBytes(UTF_8) ++ bytes).toString
  }

  def heapIds(cl: Class[_], nodes: List[TreeNode], qAdapterRegistry: QAdapterRegistry): List[SrcId] =
    for {
      node ← nodes
    } yield heapId(cl, node, qAdapterRegistry)

  def single[RespLine <: Product]: Values[Request[RespLine]] ⇒ Values[Request[RespLine]] =
    l ⇒ Single.option(l.distinct).toList

  def priority[Model <: Product](heapSrcId: SrcId, respLines: Values[Model]): Priority[Model] =
    Priority(heapSrcId, java.lang.Long.numberOfLeadingZeros(respLines.length))
}

import HashSearchMockUtils._
import K2TreeUtils._

case class MockK2Request[Model <: Product](srcId: SrcId, request: TreeRange)

case class MockNeed[Model <: Product](requestId: SrcId)

@assemble class HashSearchMockAssemble[Model <: Product](
  modelCl: Class[Model],
  getDate: Model ⇒ (Option[Long], Option[Long]),
  qAdapterRegistry: QAdapterRegistry
) extends Assemble {
  type MockHeapId = SrcId
  type MockResponseId = SrcId

  def RespLineByHeap(
    respLineId: SrcId,
    respLines: Values[Model],
    @by[All] trees: Values[TreeNodeOuter]
  ): Values[(MockHeapId, Model)] =
    for {
      respLine ← respLines
      tree ← trees
      tagId ← heapId(modelCl, findRegion(tree.root.get, getDate(respLine)), qAdapterRegistry) :: Nil
    } yield tagId → respLine

  def ReqByHeap(
    requestId: SrcId,
    requests: Values[MockK2Request[Model]],
    @by[All] trees: Values[TreeNodeOuter]
  ): Values[(MockHeapId, MockNeed[Model])] =
    for {
      request ← requests
      tree ← trees
      heapId ← heapIds(modelCl, getRegions(tree.root.get, request.request), qAdapterRegistry)
    } yield heapId → MockNeed[Model](ToPrimaryKey(request))

  def RespHeapPriorityByReq(
    heapId: SrcId,
    @by[MockHeapId] respLines: Values[Model],
    @by[MockHeapId] needs: Values[MockNeed[Model]]
  ): Values[(MockResponseId, Priority[Model])] =
    for {
      need ← needs
    } yield ToPrimaryKey(need) → priority(heapId, respLines)

  //TODO neededRespHeapPriority, respByReq, responses

}
