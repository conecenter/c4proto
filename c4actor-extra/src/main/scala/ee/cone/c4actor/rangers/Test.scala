import scala.util.Random

/*
  FROM course work
 */

case class Test() {
  def test(): Unit = {
    testCorrectness()
    val testScales = List(10, 100, 1000, 10000, 100000, 1000000)
    for (count ← testScales) {
      testRequests(count)
    }
  }

  def testRequests(elemCount: Int): Unit = {
    val dimensions = 3
    val random = new Random()
    val points = for (i ← 0 until elemCount) yield Point(i.toString, List.fill(dimensions)(random.nextInt(100) - 50))
    testRequestsTimedNoStructure(points.toList, dimensions, 1000)
    testRequestsTimedKDTree(points.toList, dimensions, 1000)

  }

  private def testRequestsTimedNoStructure(points: List[Point], dimensions: Int, requestCount: Int): Unit = {
    val structure = NoStructureRS(points)
    time(s"NS work time on ${points.size}:") {
      for (i ← 0 until requestCount) {
        val request = 0 until dimensions map (_ ⇒ {
          val l = Random.nextInt(100) - 50
          val r = l + Random.nextInt(50)
          (l, r)
        })
        structure.processRequest(Region(request.toList))
      }
    }
  }

  private def testRequestsTimedKDTree(points: List[Point], dimensions: Int, requestCount: Int): Unit = {
    val structure = KDTreeStructure(points)
    time(s"KD work time on ${points.size}:") {
      for (i ← 0 until requestCount) {
        val request = 0 until dimensions map (_ ⇒ {
          val l = Random.nextInt(100) - 50
          val r = l + Random.nextInt(50)
          (l, r)
        })
        structure.processRequest(Region(request.toList))
      }
    }
  }

  def time[R](prefix: String)(block: => R): R = {
    val t0 = System.nanoTime()
    val result = block
    val t1 = System.nanoTime()
    println(s"$prefix ${t1 - t0}")
    result
  }

  private def testCorrectness(): Unit = {
    testRealExample()
    test1D()
    test2D()
    test3D()
  }

  private def testRealExample(): Unit = {
    val pointCoordinates = List((2, 9), (3, 3), (5, 6), (6, 2), (8, 7), (9, 5))
    val points = pointCoordinates.map(tuple ⇒ Point("test", List(tuple._1, tuple._2)))
    val KDTree = KDTreeStructure(points)
    testRegion(Region(List((2, 8), (1, 8))), KDTree, List(Point("test", List(5, 6)), Point("test", List(3, 3)), Point("test", List(6, 2)), Point("test", List(8, 7))))
    testRegion(Region(List((1, 6), (5, 10))), KDTree, List(Point("test", List(5, 6)), Point("test", List(2, 9))))
    testRegion(Region(List((7, 10), (4, 9))), KDTree, List(Point("test", List(9, 5)), Point("test", List(8, 7))))
  }

  private def testRegion(region: Region, kDTreStructure: KDTreeStructure, answer: List[Point]): Unit = {
    val points = kDTreStructure.processRequest(region)
    if (answer.zip(points).map(pair ⇒ pair._1.coordinates == pair._2.coordinates).forall(c ⇒ c))
      println("RE test correct")
    else
      println("RE test incorrect")
  }

  private def test1D(): Unit = {
    val in = for (i ← 10 until 20) yield Point("in", List(i))
    val outUnder = for (i ← 0 until 9) yield Point("out", List(i))
    val outAbove = for (i ← 21 until 30) yield Point("out", List(i))
    val KDTree = KDTreeStructure(in.toList ::: outAbove.toList ::: outUnder.toList)
    val answer = KDTree.processRequest(Region(List((10, 20))))
    if (answer.exists(p ⇒ p.name == "out") && answer.size == in.size)
      println("1D test incorrect")
    else
      println("1D test correct")
  }

  private def test2D(): Unit = {
    val in = for (i ← 10 until 20; j ← 10 until 20) yield Point("in", List(i, j))
    val outUnder = for (i ← 0 until 9; j ← -100 until 100) yield Point("out", List(i, j))
    val outAbove = for (i ← 21 until 30; j ← -100 until 100) yield Point("out", List(i, j))
    val KDTree = KDTreeStructure(in.toList ::: outAbove.toList ::: outUnder.toList)
    val answer = KDTree.processRequest(Region(List((10, 20), (10, 20))))
    if (answer.exists(p ⇒ p.name == "out") && answer.size == in.size)
      println("2D test incorrect")
    else
      println("2D test correct")
  }

  private def test3D(): Unit = {
    val in = for (i ← 10 until 20; j ← 10 until 20; k ← 10 until 20) yield Point("in", List(i, j, k))
    val outUnder = for (i ← 0 until 9; j ← -100 until 100; k ← -100 until 100) yield Point("out", List(i, j, k))
    val outAbove = for (i ← 21 until 30; j ← -100 until 100; k ← -100 until 100) yield Point("out", List(i, j, k))
    val KDTree = KDTreeStructure(in.toList ::: outAbove.toList ::: outUnder.toList)
    val answer = KDTree.processRequest(Region(List((10, 20), (10, 20), (10, 20))))
    if (answer.exists(p ⇒ p.name == "out") && answer.size == in.size)
      println("3D test incorrect")
    else
      println("3D test correct")
  }
}
