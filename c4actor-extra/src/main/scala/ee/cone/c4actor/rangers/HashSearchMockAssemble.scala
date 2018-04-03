package ee.cone.c4actor.rangers

import java.nio.charset.StandardCharsets
import java.util.UUID

import ee.cone.c4actor.QAdapterRegistry
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.rangers.RangeTreeProtocol.{TreeNode, TreeNodeOuter}
import ee.cone.c4assemble.Types.Values
import StandardCharsets.UTF_8

import ee.cone.c4assemble.{Assemble, assemble, by}

object HashSearchMockUtils {
  def heapId(cl: Class[_], node: TreeNode, qAdapterRegistry: QAdapterRegistry): List[SrcId] = {
    val clName = cl.getName
    val valueAdapter = qAdapterRegistry.byName(node.getClass.getName)
    val bytes = valueAdapter.encode(node)
    UUID.nameUUIDFromBytes(clName.getBytes(UTF_8) ++ bytes).toString :: Nil
  }
}

import HashSearchMockUtils._
import K2TreeUtils._
@assemble class HashSearchMockAssemble[Model <: Product](
  modelCl: Class[Model],
  getDate: Model ⇒ (Option[Long], Option[Long]),
  qAdapterRegistry: QAdapterRegistry
) extends Assemble{
  type HeapId = SrcId
  type ResponseId = SrcId

  def RespLineByHeap(
    respLineId: SrcId,
    respLines: Values[Model]
    @by[All] trees: Values[TreeNodeOuter]
  ): Values[(HeapId, Model)] =
    for {
    respLine ← respLines
    tree ← trees
      tagId ← heapId(modelCl,findRegion(tree, getDate(respLine)), qAdapterRegistry)
  } yield tagId → respLine
}
