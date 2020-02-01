package ee.cone.c4vdom_impl

import ee.cone.c4vdom.{ChildPair, ChildPairFactory, MutableJsonBuilder, VDomValue}
import ee.cone.c4vdom.Types.{VDomKey, ViewRes}

case class ChildOrderPair[C](jsonKey: String, value: VDomValue) extends ChildPair[C] with VPair { //priv
  def key: VDomKey = throw new Exception
  def sameKey(other: VPair): Boolean = other match {
    case o: ChildOrderPair[_] => jsonKey == o.jsonKey
    case _ => false
  }
  def withValue(value: VDomValue) = copy(value=value)
}
case class ChildOrderValue(value: List[VDomKey], hint: String) extends VDomValue { //priv
  def appendJson(builder: MutableJsonBuilder) = {
    if(value.size != value.distinct.size)
      throw new Exception(s"duplicate keys: $value under $hint")

    builder.startArray()
    value.foreach(key => builder.append(LongJsonKey(key)))
    builder.end()
  }
}
case class ChildGroup(key: String, elements: ViewRes)
class ChildPairFactoryImpl(createMapValue: List[VPair]=>MapVDomValue) extends ChildPairFactory {
  def apply[C](key: VDomKey, theElement: VDomValue, elements: ViewRes): ChildPair[C] = {
    val children = group("chl",key,elements).asInstanceOf[List[VPair]]
    ChildPairImpl[C](key, createMapValue(TheElementPair(theElement) :: children))
  }
  def group(groupKey: String, hint: String, elements: ViewRes): ViewRes = (
    if(elements.isEmpty || elements.head.isInstanceOf[ChildOrderPair[_]]) elements
    else ChildOrderPair(groupKey, ChildOrderValue(elements.map(_.key), hint)) :: elements
  )
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


