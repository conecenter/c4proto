package ee.cone.c4actor.hashsearch.rangers

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.hashsearch.rangers.RangeTreeProtocol.{S_K2TreeParams, S_TreeNode, S_TreeNodeOuter, S_TreeRange}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4proto.{Id, OperativeCat, Protocol, protocol}

trait K2TreeApp extends AssemblesApp with ProtocolsApp {
  def k2ModelRegistry: List[(Class[_ <: Product], _ <: Product ⇒ (Long, Long))] = Nil

  override def assembles: List[Assemble] = k2ModelRegistry.map(pair ⇒ new K2SparkJoiner(pair._1, pair._2)) ::: super.assembles

  override def protocols: List[Protocol] = RangeTreeProtocol :: super.protocols
}

object K2TreeUtils {
  def findRegion(root: S_TreeNode, date: (Option[Long], Option[Long])): S_TreeNode =
    if (root.right.isEmpty && root.left.isEmpty)
      root
    else
      convert(date) match {
        case (x, y) if in(x, y, root.left.get.range.get) ⇒ findRegion(root.left.get, date)
        case (x, y) if in(x, y, root.right.get.range.get) ⇒ findRegion(root.right.get, date)
        case _ ⇒ throw new Exception("DynDateRanger: 26 Dot is not in any region")
      }


  def getRegions(root: S_TreeNode, search: S_TreeRange): List[S_TreeNode] =
    root match {
      case S_TreeNode(Some(_), None, None) ⇒ root :: Nil
      case S_TreeNode(_, Some(left), Some(right)) ⇒
        val answerLeft = if (fullyIn(search, left.range.get))
          getAllRegions(left)
        else if (intersectCorrect(search, left.range.get))
          getRegions(left, search)
        else Nil
        val answerRight = if (fullyIn(search, right.range.get))
          getAllRegions(right)
        else if (intersectCorrect(search, right.range.get))
          getRegions(right, search)
        else Nil
        answerLeft ::: answerRight
    }


  def fullyIn(search: S_TreeRange, b: S_TreeRange): Boolean =
    (search.minX <= b.minX) &&
      (search.minY <= b.minY) &&
      (search.maxX > b.maxX) &&
      (search.maxY > b.maxY)


  def intersectCorrect(search: S_TreeRange, b: S_TreeRange): Boolean =
    (search.minX < b.maxX) &&
      (search.minY < b.maxY) &&
      (search.maxX > b.minX) &&
      (search.maxY > b.minY)

  def inDotRange(x: Long, segment: (Long, Long)): Boolean =
    segment._1 <= x && x <= segment._2

  def getAllRegions(root: S_TreeNode): List[S_TreeNode] =
    root match {
      case S_TreeNode(Some(_), None, None) ⇒ root :: Nil
      case S_TreeNode(_, Some(left), Some(right)) ⇒ getAllRegions(left) ::: getAllRegions(right)
      case _ ⇒ throw new Exception("Node w/o left / right DynDateRanger:39")
    }

  lazy val maxValue = 3155760000000L
  lazy val minValue = 0L

  def inReg(pair: (Option[Long],Option[Long]), range:S_TreeRange): Boolean = in(convert(pair)._1, convert(pair)._2, range)
  private def in(x: Long, y: Long, range: S_TreeRange): Boolean = (range.minX <= x && x < range.maxX) && (range.minY <= y && y < range.maxY)

  private def convert(dateOpt: (Option[Long], Option[Long])): (Long, Long) =
    (dateOpt._1, dateOpt._2) match {
      case (None, None) ⇒ (maxValue, maxValue)
      case (Some(from), None) => (from, maxValue)
      case (None, Some(to)) ⇒ (minValue, to)
      case (Some(from), Some(to)) ⇒ (from, to)
    }
}

@assemble class K2SparkJoinerBase[Model <: Product](modelCl: Class[Model], modelToDate: Model ⇒ (Long, Long))   {
  def SparkK2Tree(
    paramId: SrcId,
    param: Each[S_K2TreeParams]
  ): Values[(SrcId, TxTransform)] =
    if(param.modelName == modelCl.getName)
      List(WithPK(K2TreeUpdate[Model](param.srcId, param, modelCl)(modelToDate)))
    else Nil
}

case class K2TreeUpdate[Model <: Product](srcId: SrcId, params: S_K2TreeParams, modelCl: Class[Model])(getDates: Model ⇒ (Long, Long)) extends TxTransform {
  def transform(local: Context): Context = {
    val tree = ByPK(classOf[S_TreeNodeOuter]).of(local).get(srcId)
    val now = System.currentTimeMillis()
    val doUpdate = tree.isEmpty || (now - tree.get.lastUpdateMillis >= params.updateInterval)
    if (doUpdate) {
      val dates = ByPK(modelCl).of(local).values.toList.map(model ⇒ {
        val (from, to) = getDates(model)
        Date2D(from, to)
      }
      )
      val newTree = K2Tree(dates, params.maxDepth, params.minInHeap, params.maxMinInHeap).rootNode
      TxAdd(LEvent.update(S_TreeNodeOuter(srcId, params.modelName, Option(newTree), now)))(local)
    } else {
      local
    }
  }
}

case class Date2D(x: Long, y: Long)

case class K2Tree(inputP: List[Date2D], maxDepth: Int, minInHeap: Int, maxMinInHeap: Int) {
  lazy val rootNode: S_TreeNode = getRoot()

  val minV: Long = Long.MinValue //inputP.minBy(_.x).x
  val maxV: Long = Long.MaxValue //110L
  val infRange = S_TreeRange(minV, minV, maxV, maxV)

  def getRoot(currRange: S_TreeRange = infRange, currPoints: List[Date2D] = inputP, currDepth: Int = 0): S_TreeNode = {
    if (currDepth >= maxDepth && maxMinInHeap >= currPoints.size) {
      S_TreeNode(Option(currRange), None, None)
    } else {
      currPoints.size match {
        case i if i <= minInHeap ⇒ S_TreeNode(Option(currRange), None, None)
        case _ ⇒
          val (left, median, right) = splitPointsByMedian(currPoints, currDepth)
          val incDepth = currDepth + 1
          val (leftR, rightR) = splitRegionByMedian(currRange, median, currDepth)
          S_TreeNode(Option(currRange), Option(getRoot(leftR, left, incDepth)), Option(getRoot(rightR, right, incDepth)))
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

  def splitRegionByMedian(region: S_TreeRange, split: Date2D, depth: Int): (S_TreeRange, S_TreeRange) = {
    val minX = region.minX
    val maxX = region.maxX
    val minY = region.minY
    val maxY = region.maxY
    if (depth % 2 == 0) {
      (S_TreeRange(minX, minY, split.x, maxY), S_TreeRange(split.x, minY, maxX, maxY))
    } else {
      (S_TreeRange(minX, minY, maxX, split.y), S_TreeRange(minX, split.y, maxX, maxY))
    }
  }
}

@protocol(OperativeCat) object RangeTreeProtocolBase   {

  @Id(0x0f8e) case class S_K2TreeParams(
    @Id(0x0f9b) srcId: String,
    @Id(0x0f8c) modelName: String,
    @Id(0x0f8d) updateInterval: Long,
    @Id(0x0f9c) maxDepth: Int,
    @Id(0x0f9d) minInHeap: Int,
    @Id(0x0f9e) maxMinInHeap: Int
  )

  @Id(0x0f8f) case class S_TreeNodeOuter(
    @Id(0x0f90) srcId: String,
    @Id(0x0f9b) modelName: String,
    @Id(0x0f91) root: Option[S_TreeNode],
    @Id(0x0f9f) lastUpdateMillis: Long
  )

  @Id(0x0f92) case class S_TreeNode(
    @Id(0x0f94) range: Option[S_TreeRange],
    @Id(0x0f93) right: Option[S_TreeNode],
    @Id(0x0f95) left: Option[S_TreeNode]
  )

  @Id(0x0f96) case class S_TreeRange(
    @Id(0x0f97) minX: Long,
    @Id(0x0f98) minY: Long,
    @Id(0x0f99) maxX: Long,
    @Id(0x0f9a) maxY: Long
  )

}