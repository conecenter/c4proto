package ee.cone.c4vdom_impl

import ee.cone.c4vdom.{ChildPair, ChildPairFactory, MutableJsonBuilder, ToChildPair, VDomFactory, VDomValue}
import ee.cone.c4vdom.Types.{VDomKey, ViewRes}
import scala.annotation.tailrec

case class ChildOrderPair[C](rawKey: String, value: VDomValue) extends ChildPair[C] with VPair { //priv
  def jsonKey: String = s"@${rawKey}"
  def key: VDomKey = throw new Exception(s"$jsonKey -- $value")
  def sameKey(other: VPair): Boolean = other match {
    case o: ChildOrderPair[_] => rawKey == o.rawKey
    case _ => false
  }
  def withValue(value: VDomValue) = copy(value=value)
}
case class ChildOrderValue(value: Seq[VDomKey], hint: String) extends VDomValue { //priv
  def appendJson(builder: MutableJsonBuilder) = {
    if(value.size != value.distinct.size) throw new DuplicateKeysExceptionImpl(this)
    builder.startArray()
    value.foreach(key => builder.append(LongJsonKey(key)))
    builder.end()
  }
}
case class ChildGroup(key: String, elements: ViewRes)
class ChildPairFactoryImpl(inner: VDomFactory) extends ChildPairFactory {
  def apply[C](key: VDomKey, theElement: VDomValue, elements: List[ChildPair[_]]): ChildPair[C] =
    inner.create(key,theElement,inner.addGroup(key,"children",elements,Nil))
}

class VDomFactoryImpl(createMapValue: List[VPair]=>MapVDomValue) extends VDomFactory {
  def create[C](key: VDomKey, theElement: VDomValue, elements: List[ChildPair[_]]): ChildPair[C] =
    ChildPairImpl[C](key, createMapValue(TheElementPair(theElement) :: elements.asInstanceOf[List[VPair]]))
  def addGroup(key: String, groupKey: String, elements: Seq[ChildPair[_]], res: Seq[ChildPair[_]]): List[ChildPair[_]] =
    if(elements.isEmpty) res.toList else
    ChildOrderPair(groupKey, ChildOrderValue(elements.map(getKey), key)) :: elements ++: res.toList //elements.foldLeft(res)((res,el)=>)
  //def addGroup(key: String, groupKey: String, element: ChildPair[_], res: ViewRes): ViewRes =
  //  ChildOrderPair(groupKey, ChildOrderValue(Seq(getKey(element)), key)) :: element :: res
  def getKey(pair: ChildPair[_]): VDomKey = pair match {
    case o: ChildPairImpl[_] => o.key
  }
}

object LongJsonKey { def apply(key: VDomKey) = s":$key" }
case class ChildPairImpl[C](key: VDomKey, value: VDomValue) extends ChildPair[C] with VPair { //pub
  def jsonKey = LongJsonKey(key)
  def sameKey(other: VPair) = other match {
    case o: ChildPairImpl[_] => key == o.key
    case _ => false
  }
  def withValue(value: VDomValue) = copy(value=value)
}

case class TheElementPair(value: VDomValue) extends VPair { //priv
  def jsonKey = "at"
  def sameKey(other: VPair) = other match {
    case v: TheElementPair => true
    case _ => false
  }
  def withValue(value: VDomValue) = copy(value=value)
}

// this is not optimal -- recreates the tree, because we failed with bad tree anyway
// this does not try to resolve complex things like multi-orders etc
object FixDuplicateKeysImpl extends FixDuplicateKeys {
  def fix(ex: DuplicateKeysException, value: VDomValue): VDomValue = ex match { case e: DuplicateKeysExceptionImpl =>
      fix(e.value.value.groupBy(k=>k).collect{ case (k,v) if v.size > 1 => k }.min, value)
  }
  private def isBadM(bad: VDomKey)(p: VPair): Boolean =
    p match { case c: ChildPairImpl[_] if c.key==bad => true case _ => false }
  private def withCountM(i: Int): VPair=>VPair = { case e: ChildPairImpl[_] => e.copy(key=s"${e.key}-$i") }
  private def fix(bad: VDomKey, value: VDomValue): VDomValue = value match {
    case v: MapVDomValue =>
      MapVDomValueImpl(fixList[VPair](isBadM(bad), withCountM, v.pairs, 0, Nil).map(p=>p.withValue(fix(bad,p.value))))
    case v: ChildOrderValue => ChildOrderValue(fixList[VDomKey](_==bad, i=>k=>s"$k-$i", v.value.toList, 0, Nil), v.hint)
    case v => v
  }
  @tailrec private def fixList[A](
    isBad: A=>Boolean, withCount: Int=>A=>A, list: List[A], count: Int, res: List[A]
  ): List[A] = if(list.isEmpty) res.reverse else {
    val isBadR = isBad(list.head)
    val willEl = if(isBadR && count > 0) withCount(count)(list.head) else list.head
    fixList(isBad, withCount, list.tail, if(isBadR) count + 1 else count, willEl :: res)
  }
}
class DuplicateKeysExceptionImpl(val value: ChildOrderValue)
  extends Exception(s"duplicate keys: ${value.value} under ${value.hint}") with DuplicateKeysException