package ee.cone.c4actor

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.AnyOrigProtocol.AnyOrig
import ee.cone.c4proto._

object AnyAdapter {
  def decodeOpt[Model <: Product](qAdapterRegistry: QAdapterRegistry): AnyOrig ⇒ Option[Model] = any ⇒ {
    qAdapterRegistry.byId.get(any.adapterId).map(adapter ⇒
      adapter.decode(any.value).asInstanceOf[Model]
    )
  }

  def decode[Model <: Product](qAdapterRegistry: QAdapterRegistry): AnyOrig ⇒ Model = any ⇒ {
    val adapter = qAdapterRegistry.byId(any.adapterId)
    adapter.decode(any.value).asInstanceOf[Model]
  }

  def decodeProduct(qAdapterRegistry: QAdapterRegistry): AnyOrig ⇒ Product = any ⇒ {
    val adapter = qAdapterRegistry.byId(any.adapterId)
    adapter.decode(any.value)
  }

  def decode[Model <: Product](qAdapterRegistry: QAdapterRegistry, modelCl: Class[Model]): AnyOrig ⇒ Model = any ⇒ {
    val adapter = qAdapterRegistry.byId(any.adapterId)
    adapter.decode(any.value).asInstanceOf[Model]
  }

  def encode[Model <: Product](qAdapterRegistry: QAdapterRegistry): Model ⇒ AnyOrig = model ⇒ {
    val adapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(model.getClass.getName)
    val byteString = adapter.encode(model)
    val adapterId = adapter.id
    AnyOrig(adapterId, ToByteString(byteString))
  }

  def encode[Model <: Product](qAdapterRegistry: QAdapterRegistry, modelCl: Class[Model]): Model ⇒ AnyOrig = model ⇒ {
    val adapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(model.getClass.getName)
    val byteString = adapter.encode(model)
    val adapterId = adapter.id
    AnyOrig(adapterId, ToByteString(byteString))
  }
}

@protocol(InnerCat) object AnyOrigProtocol   {

  case class AnyOrig(
    @Id(0x00ab) adapterId: Long,
    @Id(0x00ac) value: okio.ByteString
  )

}
