package ee.cone.c4vdom_impl

import ee.cone.c4vdom.{MutableJsonBuilder, Resolvable, ResolvingVDomValue}

case class MapVDomValueImpl(pairs: List[VPair]) extends MapVDomValue with ResolvingVDomValue {
  def appendJson(builder: MutableJsonBuilder) = {
    builder.startObject()
    pairs.foreach{ p =>
      builder.just.append(p.jsonKey)
      p.value.appendJson(builder)
    }
    builder.end()
  }
  def resolve(name: String): Option[Resolvable] =
    pairs.find(_.jsonKey == name).map(_.value)
}
