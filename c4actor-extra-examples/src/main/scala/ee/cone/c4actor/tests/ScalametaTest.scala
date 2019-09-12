package ee.cone.c4actor.tests

import scala.collection.immutable.Seq
import scala.meta.Term.Name
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
      case _ ⇒ 666 :: Nil
    })

    val defenition = "@ignore def test(arg: Int): LUL = {val a = 3}".parse[Stat].get
    defenition match {
      case q"@ignore def $ename[..$tparams](...$paramss): $tpeopt = $expr" ⇒ println("def ok")
      case _ ⇒ println("not ok")
    }

    def parseArgs: Seq[Seq[Term]] ⇒ List[String] =
      _.flatMap(_.map(_.toString())).toList

    val text = "@Meta(Atata, ee.cone.ee.Totoot(1,1,1)) val a = 1".parse[Stat].get
    text match {
      case q"@Meta(...$exprss) val a = 1" ⇒ println(parseArgs(exprss))
      case _ ⇒ Nil
    }
  }
}
