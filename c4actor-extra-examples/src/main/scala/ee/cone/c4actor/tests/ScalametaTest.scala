package ee.cone.c4actor.tests

import scala.meta._

object ScalametaTest {
  def main(args: Array[String]): Unit = {
    val s = "@ignore val test: Int = 1".parse[Stat].get

    def parseType(t: Type): String = {
      t match {
        case t"$tpe[..$tpesnel]" => s"""ee.cone.c4proto.TypeProp(classOf[$tpe[${tpesnel.map(_ ⇒ "_").mkString(", ")}]].getName, "$tpe", ${tpesnel.map(parseType)})"""
        case t"$tpe" ⇒ s"""ee.cone.c4proto.TypeProp(classOf[$tpe].getName, "$tpe", Nil)"""
      }
    }
    //println(parseType(s))
    //println(s)
    s match {
      case q"@ignore val ..$vname: $tpe = $expr" ⇒ Nil
      case _ ⇒ throw new Exception("fail fish")
    }
  }
}
