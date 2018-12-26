package ee.cone.xml

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.QAdapterRegistry
import ee.cone.c4proto.{HasId, XMLCat}

class XMLParser(
  qAdapterRegistry: QAdapterRegistry
) {
  val xmlOrigs: Map[String, ProtoAdapter[Product] with HasId] = qAdapterRegistry.byName.filter(_._2.categories.contains(XMLCat))

}
