
package ee.cone.c4actor


case class StrEq(value: String) //todo proto
case object StrEqCheck extends ConditionCheck[StrEq,String] {
  def check: StrEq ⇒ String ⇒ Boolean = by ⇒ value ⇒ value == by.value
}
case object StrEqRanger extends Ranger[StrEq,String] {
  def ranges: StrEq ⇒ (String ⇒ List[StrEq], PartialFunction[Product,List[StrEq]]) = {
    case StrEq("") ⇒ (
      value ⇒ List(StrEq(value)),
      { case p@StrEq(v) ⇒ List(p) }
    )
  }
}
object DefaultConditionChecks {
  implicit lazy val strEq: ConditionCheck[StrEq,String] = StrEqCheck
}
object DefaultRangers {
  implicit lazy val strEq: Ranger[StrEq,String] = StrEqRanger
}

//todo reg
trait MyApp {
  def modelConditionFactory[Unit]: ModelConditionFactory[Unit]
  lazy val hashSearchFactory: HashSearch.Factory =
    new HashSearchImpl.FactoryImpl(modelConditionFactory)
}
import DefaultConditionChecks._
import DefaultRangers._

/*
override def assembles = ...
hashSearchFactory.index(classOf[SomeModel])
  .add(aLens, StrEq(""))
  .add(bLens, StrEq(""))
  .assemble

: Values[(SrcId,Request[SomeModel])] = ...
import modelConditionFactory.of[SomeModel]._
hashSearchFactory.request(intersect(
  leaf(aLens, StrEq(aVal)),
  leaf(bLens, StrEq(bVal))
))
*/
