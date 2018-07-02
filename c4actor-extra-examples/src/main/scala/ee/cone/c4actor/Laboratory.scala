package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId

object Laboratory {
  def main(args: Array[String]): Unit = {
    val world = for{
      i ← 1 to 50000
    } yield A(i.toString, i)
    val list1 = world.slice(0, 20000).toList
    val list2 = world.slice(10000,50000).toList
    val list3 = world.slice(20000,350000).toList
    val list4 = world.slice(35000,49000).toList
    val list5 = world.slice(0,50000).toList

    TimeColored("r", "test") {
      println(MergeBySrcId(scala.collection.immutable.Seq(list1, list2, list3, list4, list5)).map(_.srcId).distinct.size)
    }

    H().printList
  }

  case class A(srcId: SrcId, value: Int)
}

trait A {
  def list: List[Product] = Nil
}

trait B extends A {
  override def list: List[Product] = (1, 2) :: super.list
}

trait D extends B {
  override def list: List[Product] = (1, 3) :: super.list
}

trait E extends B {
  override def list: List[Product] = (1, 4) :: super.list
}

case class H() extends D with E {
  def printList = println(list)
}

