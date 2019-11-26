package ee.cone.c4vdom_impl

import ee.cone.c4vdom.{ChildPair, ChildPairFactory, MutableJsonBuilder, VDomValue}
import ee.cone.c4vdom.Types.{VDomKey, ViewRes}

case class ChildOrderPair(value: VDomValue) extends VPair { //priv
  def jsonKey = "chl"
  def sameKey(other: VPair) = other match {
    case v: ChildOrderPair => true
    case _ => false
  }
  def withValue(value: VDomValue) = copy(value=value)
}
case class ChildOrderValue(value: List[VDomKey], parent: VDomKey) extends VDomValue { //priv
  def appendJson(builder: MutableJsonBuilder) = {
    if(value.size != value.distinct.size)
      throw new Exception(s"duplicate keys: $value under $parent")

    builder.startArray()
    value.foreach(key => builder.append(LongJsonKey(key)))
    builder.end()
  }
}

class ChildPairFactoryImpl(createMapValue: List[VPair]=>MapVDomValue) extends ChildPairFactory {
  def apply[C](
    key: VDomKey,
    theElement: VDomValue,
    elements: ViewRes
  ): ChildPair[C] = ChildPairImpl[C](key, createMapValue(
    TheElementPair(theElement) :: (
      if(elements.isEmpty) Nil
      else (ChildOrderPair(ChildOrderValue(elements.map(_.key), key)) :: elements).asInstanceOf[List[VPair]]
    )
  ))
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


