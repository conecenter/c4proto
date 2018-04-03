package ee.cone.c4actor.rangers

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.rangers.RangeTreeProtocol.{K2TreeParams, TreeNode, TreeNodeOuter, TreeRange}
import ee.cone.c4proto.{Id, Protocol, protocol}

case class K2TreeUpdate[Model <: Product](srcId: SrcId, params: K2TreeParams, modelCl: Class[Model])(getDates: Model ⇒ (Long, Long)) extends TxTransform {
  def transform(local: Context): Context = {
    val tree = ByPK(classOf[TreeNodeOuter]).of(local).get(srcId)
    val now = System.currentTimeMillis()
    val doUpdate = tree.isEmpty || (now - tree.get.lastUpdateMillis >= params.updateInterval)
    if (doUpdate) {
      val dates = ByPK(modelCl).of(local).values.toList.map(model ⇒ {
        val (from, to) = getDates(model)
        Date2D(from, to)
      }
      )
      val newTree = K2Tree(dates, params.maxDepth, params.minInHeap, params.maxMinInHeap).rootNode
      TxAdd(LEvent.update(TreeNodeOuter(srcId, Option(newTree), now)))(local)
    } else {
      local
    }
  }
}

case class Date2D(x: Long, y: Long)

case class K2Tree(inputP: List[Date2D], maxDepth: Int, minInHeap: Int, maxMinInHeap: Int) {
  lazy val rootNode: TreeNode = getRoot()

  val minV: Long = Long.MinValue //inputP.minBy(_.x).x
  val maxV: Long = Long.MaxValue //110L
  val infRange = TreeRange(minV, minV, maxV, maxV)

  def getRoot(currRange: TreeRange = infRange, currPoints: List[Date2D] = inputP, currDepth: Int = 0): TreeNode = {
    if (currDepth >= maxDepth && maxMinInHeap >= currPoints.size) {
      TreeNode(Option(currRange), None, None)
    } else {
      currPoints.size match {
        case i if i <= minInHeap ⇒ TreeNode(Option(currRange), None, None)
        case _ ⇒
          val (left, median, right) = splitPointsByMedian(currPoints, currDepth)
          val incDepth = currDepth + 1
          val (leftR, rightR) = splitRegionByMedian(currRange, median, currDepth)
          TreeNode(Option(currRange), Option(getRoot(leftR, left, incDepth)), Option(getRoot(rightR, right, incDepth)))
      }
    }
  }

  def splitPointsByMedian(ds: List[Date2D], i: Int): (List[Date2D], Date2D, List[Date2D]) = {
    val sortBy: Date2D ⇒ Long = if (i % 2 == 0) _.x else _.y
    val sortBy2: Date2D ⇒ Long = if (i % 2 == 1) _.y else _.x
    val sortedPoints = ds.sortBy(sortBy).sortBy(sortBy2)
    val medianIndex = ds.size / 2
    (sortedPoints.take(medianIndex), sortedPoints(medianIndex), sortedPoints.drop(medianIndex))
  }

  def splitRegionByMedian(region: TreeRange, split: Date2D, depth: Int): (TreeRange, TreeRange) = {
    val minX = region.minX
    val maxX = region.maxX
    val minY = region.minY
    val maxY = region.maxY
    if (depth % 2 == 0) {
      (TreeRange(minX, minY, split.x, maxY), TreeRange(split.x, minY, maxX, maxY))
    } else {
      (TreeRange(minX, minY, maxX, split.y), TreeRange(minX, split.y, maxX, maxY))
    }
  }
}

@protocol object RangeTreeProtocol extends Protocol {

  @Id(0x0f8e) case class K2TreeParams(
    @Id(0x0f9b) srcId: String,
    @Id(0x0f8c) modelName: String,
    @Id(0x0f8d) updateInterval: Long,
    @Id(0x0f9c) maxDepth: Int,
    @Id(0x0f9d) minInHeap: Int,
    @Id(0x0f9e) maxMinInHeap: Int
  )

  @Id(0x0f8f) case class TreeNodeOuter(
    @Id(0x0f90) srcId: String,
    @Id(0x0f91) root: Option[TreeNode],
    @Id(0x0f9f) lastUpdateMillis: Long
  )

  @Id(0x0f92) case class TreeNode(
    @Id(0x0f94) range: Option[TreeRange],
    @Id(0x0f93) right: Option[TreeNode],
    @Id(0x0f95) left: Option[TreeNode]
  )

  @Id(0x0f96) case class TreeRange(
    @Id(0x0f97) minX: Long,
    @Id(0x0f98) minY: Long,
    @Id(0x0f99) maxX: Long,
    @Id(0x0f9a) maxY: Long
  )

}