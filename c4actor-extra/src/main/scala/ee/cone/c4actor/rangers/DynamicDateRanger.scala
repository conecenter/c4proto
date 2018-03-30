package ee.cone.c4actor.rangers

trait Dynamic2DDateRanger {
  def get2DRanges(approxCount: Int): DateRange2D

  def get2DRegions(findIn: DateRange2D): List[DateRange2D]
}

case class Date2D(x: Long, y: Long)

case class DateRange2D(from: Date2D, to: Date2D)

class K2Tree(points: List[Date2D], maxDepth: Int) {
  lazy val regions: List[DateRange2D] = getRegions()

  val infRange = DateRange2D(Date2D(Long.MinValue, Long.MaxValue), Date2D(Long.MaxValue, Long.MinValue))

  def getRegions(currPoints: List[Date2D] = points, currDepth: Int = 0): List[DateRange2D] = {
    if (currDepth > maxDepth) {
      makeRegion(currPoints) :: Nil
    } else {
      points.size match {
        case 0 | 1 ⇒ throw new Exception("This shouldn't happen")
        case 2 | 3 ⇒ makeRegion(currPoints) :: Nil
        case _ ⇒
          val (left, right) = splitByMedian(points, currDepth)
          val incDepth = currDepth + 1
          getRegions(left, incDepth) ::: getRegions(right, incDepth)
      }
    }
  }

  def splitByMedian(ds: List[Date2D], i: Int): (List[Date2D], List[Date2D]) = {
    val sortBy: Date2D ⇒ Long = if (i % 2 == 0) _.x else _.y
    val sortBy2: Date2D ⇒ Long = if (i % 2 == 1) _.x else _.y
    val sortedPoints = points.sortBy(sortBy).sortBy(sortBy2)
    val medianIndex = ds.size / 2
    (sortedPoints.take(medianIndex), sortedPoints.drop(medianIndex))
  }


  def makeRegion(points: List[Date2D]): DateRange2D = {
    if (points.nonEmpty) {
      val minX = points.minBy(_.x).x
      val maxX = points.maxBy(_.x).x
      val minY = points.minBy(_.y).y
      val maxY = points.maxBy(_.y).y
      coordinatesToRegion(minX, minY, maxX, maxY)
    } else {
      throw new Exception("Leaf can't be with empty list")
    }
  }

  def coordinatesToRegion(xl: Long, yt: Long, xr: Long, yb: Long): DateRange2D =
    DateRange2D(Date2D(xl, yt), Date2D(xr, yb))
}