/*
  FROM course work
 */

case class Region(borders: List[(Int, Int)]) {

  def split(dimension: Int, point: Point): (Region, Region) = {
    val medianValue = point.coordinates(dimension)
    val left = Region(borders.updated(dimension, (borders(dimension)._1, medianValue)))
    val right = Region(borders.updated(dimension, (medianValue, borders(dimension)._2)))
    (left, right)
  }

  def in(other: Region): Boolean = {
    val otherBorders = other.borders
    val checks = for (i ← borders.indices) yield {
      val (ol, or) = otherBorders(i)
      val (cl, cr) = borders(i)
      ol <= cl && or >= cr
    }
    checks.foldLeft(true)((z, c) ⇒ z && c)
  }

  def intersects(other: Region): Boolean = {
    val otherBorders = other.borders
    val checks = for (i ← borders.indices) yield {
      val (ol, or) = otherBorders(i)
      val (cl, cr) = borders(i)
      (ol <= cl && cl <= or && or <= cr) || (cl <= ol && ol <= cr && cr <= or)
    }
    checks.foldLeft(false)((z, c) ⇒ z || c)
  }
}

case class Point(name: String, coordinates: List[Int]) extends Ordering[Point] {
  override def toString: String = s"$name $coordinates"

  def in(region: Region): Boolean = {
    val borders = region.borders
    val checks = for (i ← coordinates.indices) yield {
      val (rl, rr) = borders(i)
      val dot = coordinates(i)
      rl < dot && dot < rr
    }
    checks.foldLeft(true)((z, c) ⇒ z && c)
  }

  def produceRegion: Region = {
    Region(coordinates.map(x ⇒ (x, x)))
  }

  override def compare(x: Point, y: Point): Int = {
    val cmp = for (i ← x.coordinates.indices) yield {
      x.coordinates(i).compare(y.coordinates(i))
    }
    val index = cmp.takeWhile(i ⇒ i == 0).size
    if (index < cmp.size) {
      cmp(index)
    } else {
      0
    }
  }
}

object Consts {
  lazy val INFINITY = 1000000

  def infiniteRegion(dimensions: Int): Region = Region(List.fill(dimensions)((-INFINITY, INFINITY)))
}
