/*
  FROM course work
 */

trait RegionSearchStructure {
  def processRequest(request: Region): List[Point]

  def getSize: Int
}

case class NoStructureRS(points: List[Point]) extends RegionSearchStructure {

  override def processRequest(request: Region): List[Point] = points.filter(point ⇒ point.in(request))

  override def getSize: Int = 0
}

case class KDTreeStructure(points: List[Point]) extends RegionSearchStructure {
  private lazy val searchStructure: Node = buildTree(points, Consts.infiniteRegion(points.head.coordinates.size)).get

  override def processRequest(request: Region): List[Point] = findInTree(request, searchStructure)

  override def getSize: Int = points.size

  private def findInTree(request: Region, current: Node): List[Point] = {
    current match {
      case current: LeafNode ⇒ pointInRequest(request, current.median)
      case current: TreeNode ⇒
        val middle = if (current.median.in(request)) current.median :: Nil else Nil
        val left = findInSubTree(request, current.left)
        val right = findInSubTree(request, current.right)
        middle ::: left ::: right
    }
  }

  private def findInSubTree(request: Region, current: Option[Node]): List[Point] = {
    current match {
      case Some(node) ⇒
        if (node.region.in(request)) returnTreePoints(node)
        else if (node.region.intersects(request)) findInTree(request, node)
        else Nil
      case None ⇒ Nil
    }
  }

  private def returnTreePoints(current: Node): List[Point] = {
    current match {
      case current: LeafNode ⇒ current.median :: Nil
      case current: TreeNode ⇒
        val leftAnswer = current.left.map(node ⇒ returnTreePoints(node))
        val rightAnswer = current.right.map(node ⇒ returnTreePoints(node))
        current.median :: leftAnswer.getOrElse(Nil) ::: rightAnswer.getOrElse(Nil)
    }
  }

  private def pointInRequest(request: Region, point: Point): List[Point] = {
    if (point.in(request)) point :: Nil else Nil
  }

  private def splitByMedian(points: List[Point], depth: Int): (List[Point], Point, List[Point]) = {
    val sortedPoints = points.sortBy(_.coordinates(depth))
    val medianValue: Int = points.foldLeft(0)((z, point) ⇒ z + point.coordinates(depth)) / points.size
    val (left, medRight) = sortedPoints.partition(_.coordinates(depth) < medianValue)
    val median = medRight.headOption
    median match {
      case Some(point) ⇒
        val right = medRight.tail
        (left, point, right)
      case None ⇒
        (sortedPoints.init, sortedPoints.last, List())
    }
  }

  private def buildTree(points: List[Point], region: Region, depth: Int = 0): Option[Node] = {
    points.size match {
      case 0 ⇒ None
      case 1 ⇒ Option(LeafNode(depth, points.head, points.head.produceRegion))
      case _ ⇒
        val (left, median, right) = splitByMedian(points, depth)
        val nextDepth = (depth + 1) % points.head.coordinates.size
        val (leftRegion, rightRegion) = region.split(depth, median)
        val leftChild = buildTree(left, leftRegion, nextDepth)
        val rightChild = buildTree(right, rightRegion, nextDepth)
        Option(TreeNode(depth, median, leftChild, rightChild, region))
    }
  }

  private trait Node {
    val dimension: Int
    val median: Point
    val region: Region
  }

  private case class TreeNode(dimension: Int, median: Point, left: Option[Node], right: Option[Node], region: Region) extends Node

  private case class LeafNode(dimension: Int, median: Point, region: Region) extends Node

}
