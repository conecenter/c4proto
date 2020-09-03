package ee.cone.c4vdom_impl

import ee.cone.c4vdom.{ChildPair, ChildPairFactory, MutableJsonBuilder, VDomFactory, VDomValue}
import ee.cone.c4vdom.Types.{VDom, VDomKey, ViewRes}

case class ChildOrderPair[C](jsonKey: String, value: VDomValue) extends ChildPair[C] with VPair { //priv
  def key: VDomKey = throw new Exception
  def sameKey(other: VPair): Boolean = other match {
    case o: ChildOrderPair[_] => jsonKey == o.jsonKey
    case _ => false
  }
  def withValue(value: VDomValue) = copy(value=value)
}
case class ChildOrderValue(value: Seq[VDomKey], hint: String) extends VDomValue { //priv
  def appendJson(builder: MutableJsonBuilder) = {
    if(value.size != value.distinct.size)
      throw new Exception(s"duplicate keys: $value under $hint")

    builder.startArray()
    value.foreach(key => builder.append(LongJsonKey(key)))
    builder.end()
  }
}
case class ChildGroup(key: String, elements: ViewRes)
class ChildPairFactoryImpl(inner: VDomFactory) extends ChildPairFactory {
  def apply[C](key: VDomKey, theElement: VDomValue, elements: ViewRes): ChildPair[C] =
    inner.create(key,theElement,inner.addGroup(key,"chl",elements,Nil))
}

class VDomFactoryImpl(createMapValue: List[VPair]=>MapVDomValue) extends VDomFactory {
  def create[C](key: VDomKey, theElement: VDomValue, elements: ViewRes): VDom[C] =
    ChildPairImpl[C](key, createMapValue(TheElementPair(theElement) :: elements.asInstanceOf[List[VPair]]))
  def addGroup(key: String, groupKey: String, elements: Seq[VDom[_]], res: ViewRes): ViewRes =
    ChildOrderPair(groupKey, ChildOrderValue(elements.map(_.key), key)) :: elements ++: res //elements.foldLeft(res)((res,el)=>)
  def addGroup(key: String, groupKey: String, element: VDom[_], res: ViewRes): ViewRes =
    ChildOrderPair(groupKey, ChildOrderValue(Seq(element.key), key)) :: element :: res
}

object LongJsonKey { def apply(key: VDomKey) = s":$key" }
case class ChildPairImpl[C](key: VDomKey, value: VDomValue) extends ChildPair[C] with VPair { //pub
  def jsonKey = LongJsonKey(key)
  def sameKey(other: VPair) = other match {
    case o: ChildPair[_] => key == o.key
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


