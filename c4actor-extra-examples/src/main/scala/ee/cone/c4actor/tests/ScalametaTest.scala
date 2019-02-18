package ee.cone.c4actor.tests

import scala.meta._

object ScalametaTest {
  def main(args: Array[String]): Unit = {
    val s = "Option[Long]".parse[Type].get

    def parseType(t: Type): String = {
      t match {
        case t"$tpe[..$tpesnel]" => s"""ee.cone.c4proto.TypeProp(classOf[$tpe[${tpesnel.map(_ ⇒ "_").mkString(", ")}]].getName, "$tpe", ${tpesnel.map(parseType)})"""
        case t"$tpe" ⇒ s"""ee.cone.c4proto.TypeProp(classOf[$tpe].getName, "$tpe", Nil)"""
      }
    }
    println(parseType(s))
    //println(s)
  }
}
