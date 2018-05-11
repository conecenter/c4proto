package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.utils.GeneralizedOrigFactory

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
  }

  case class A(srcId: SrcId, value: Int)
}

