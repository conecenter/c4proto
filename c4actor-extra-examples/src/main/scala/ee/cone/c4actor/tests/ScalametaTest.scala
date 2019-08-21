package ee.cone.c4actor.tests

import scala.meta._

object ScalametaTest {
  def main(args: Array[String]): Unit = {
    val s = "override val test: List[Int] = 1 ::: super.test".parse[Stat].get

    def parseType(t: Type): String = {
      t match {
        case t"$tpe[..$tpesnel]" => s"""ee.cone.c4proto.TypeProp(classOf[$tpe[${tpesnel.map(_ ⇒ "_").mkString(", ")}]].getName, "$tpe", ${tpesnel.map(parseType)})"""
        case t"$tpe" ⇒ s"""ee.cone.c4proto.TypeProp(classOf[$tpe].getName, "$tpe", Nil)"""
      }
    }
    //println(parseType(s))
    //println(s)
    println(s match {
      case q"override val ..$vname: $tpe = $expr" ⇒
        expr.collect({
          case q"super.test" ⇒ 1
          case _ ⇒ 0
        })
      case _ ⇒ throw new Exception("fail fish")
    })
  }
}
