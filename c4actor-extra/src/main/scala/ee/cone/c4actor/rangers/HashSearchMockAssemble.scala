package ee.cone.c4actor.rangers

import java.nio.charset.StandardCharsets
import java.util.UUID

import ee.cone.c4actor.QAdapterRegistry
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.rangers.RangeTreeProtocol.{K2TreeParams, TreeNode, TreeNodeOuter, TreeRange}
import ee.cone.c4assemble.Types.Values
import StandardCharsets.UTF_8

import ee.cone.c4actor.HashSearch.Request
import ee.cone.c4actor.HashSearchImpl.{Need, Priority}
import ee.cone.c4actor.rangers.K2TreeUtils._
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

  def single[Model <: Product]: Values[MockK2Request[Model]] ⇒ Values[MockK2Request[Model]] =
    l ⇒ Single.option(l.distinct).toList

  def priority[Model <: Product](heapSrcId: SrcId, counts: Values[MockCount[Model]]): Priority[Model] =
    Priority(heapSrcId, java.lang.Long.numberOfLeadingZeros(counts.foldLeft(0)((z, count) ⇒ z + count.count)))

  def count[Model <: Product](heapSrcId: SrcId, respLines: Values[Model]): MockCount[Model] =
    MockCount(heapSrcId, respLines.length)
}

import HashSearchMockUtils._
import K2TreeUtils._

case class MockK2Request[Model <: Product](srcId: SrcId, request: TreeRange)

case class MockNeed[Model <: Product](requestId: SrcId, modelCl: Class[Model])

case class MockCount[Model <: Product](heapSrcId: SrcId, count: Int)

@assemble class HashSearchMockAssemble[RespLine <: Product](
  modelCl: Class[RespLine],
  getDate: RespLine ⇒ (Option[Long], Option[Long]),
  qAdapterRegistry: QAdapterRegistry
) extends Assemble {
  type MockHeapId = SrcId
  type MockToCountId = SrcId
  type MockResponseId = SrcId
  type ResponseId = SrcId
  type K2TreeAll[Test] = All

  def GetTreeToAll(
    treeId: SrcId,
    params: Values[K2TreeParams],
    trees: Values[TreeNodeOuter]
  ): Values[(K2TreeAll[RespLine], TreeNodeOuter)] =
    for {
      param ← params
      if param.modelName == modelCl.getName
      tree ← trees
    } yield All → tree

  def RespLineByHeap(
    respLineId: SrcId,
    respLines: Values[RespLine],
    @by[K2TreeAll[RespLine]] trees: Values[TreeNodeOuter]
  ): Values[(MockHeapId, RespLine)] =
    for {
      respLine ← respLines
      tree ← trees
      tagId ← heapId(modelCl, findRegion(tree.root.get, getDate(respLine)), qAdapterRegistry) :: Nil
    } yield tagId → respLine

  def ReqByHeap(
    requestId: SrcId,
    requests: Values[MockK2Request[RespLine]],
    @by[K2TreeAll[RespLine]] trees: Values[TreeNodeOuter]
  ): Values[(MockHeapId, MockNeed[RespLine])] =
    for {
      request ← single(requests)
      tree ← trees
      heapId ← heapIds(modelCl, getRegions(tree.root.get, request.request), qAdapterRegistry) // TODO makes full Id list for given cond
    } yield heapId → MockNeed[RespLine](ToPrimaryKey(request), modelCl)

  def RespHeapCountByReq(
    heapId: SrcId,
    @by[MockHeapId] respLines: Values[RespLine],
    @by[MockHeapId] needs: Values[MockNeed[RespLine]]
  ): Values[(MockToCountId, MockCount[RespLine])] =
    for {
      need ← needs
    } yield need.requestId → count(heapId, respLines) // TODO Calculated for given heap

  def CountByReq(
    needId: MockHeapId,
    requests: Values[MockK2Request[RespLine]],
    @by[MockToCountId] counts: Values[MockCount[RespLine]]
  ): Values[(ResponseId, Priority[RespLine])] =
    for {
      request ← requests
    } yield request.srcId → priority(request.srcId, counts)

  def RespByReq(
    heapId: SrcId,
    @by[MockHeapId] responses: Values[RespLine],
    @by[MockHeapId] requests: Values[MockK2Request[RespLine]]
  ): Values[(ResponseId, RespLine)] =
    for {
      request ← requests
      line ← responses
      if inReg(getDate(line), request.request)
    } yield ToPrimaryKey(request) → line

  //TODO inputRequests and SK joiners addons

}
