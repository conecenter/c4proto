package ee.cone.c4vdom_impl

import ee.cone.c4vdom.MutableJsonBuilder

case class MapVDomValueImpl(pairs: List[VPair]) extends MapVDomValue {
  def appendJson(builder: MutableJsonBuilder) = {
    builder.startObject()
    pairs.foreach{ p =>
      builder.append(p.jsonKey)
      p.value.appendJson(builder)
    }
    builder.end()
  }
}
