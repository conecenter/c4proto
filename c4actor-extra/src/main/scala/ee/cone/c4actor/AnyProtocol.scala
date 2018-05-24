package ee.cone.c4actor

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.AnyProtocol.AnyObject
import ee.cone.c4proto._

object AnyAdapter {
  def decodeOpt[Model <: Product](qAdapterRegistry: QAdapterRegistry): AnyObject ⇒ Option[Model] = any ⇒ {
    qAdapterRegistry.byId.get(any.adapterId).map(adapter ⇒
      adapter.decode(any.value).asInstanceOf[Model]
    )
  }

  def decode[Model <: Product](qAdapterRegistry: QAdapterRegistry): AnyObject ⇒ Model = any ⇒ {
    val adapter = qAdapterRegistry.byId(any.adapterId)
    adapter.decode(any.value).asInstanceOf[Model]
  }

  def decode[Model <: Product](qAdapterRegistry: QAdapterRegistry, modelCl: Class[Model]): AnyObject ⇒ Model = any ⇒ {
    val adapter = qAdapterRegistry.byId(any.adapterId)
    adapter.decode(any.value).asInstanceOf[Model]
  }

  def encode[Model <: Product](qAdapterRegistry: QAdapterRegistry): Model ⇒ AnyObject = model ⇒ {
    val adapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(model.getClass.getName)
    val byteString = adapter.encode(model)
    val adapterId = adapter.id
    AnyObject(adapterId, ToByteString(byteString))
  }

  def encode[Model <: Product](qAdapterRegistry: QAdapterRegistry, modelCl: Class[Model]): Model ⇒ AnyObject = model ⇒ {
    val adapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(model.getClass.getName)
    val byteString = adapter.encode(model)
    val adapterId = adapter.id
    AnyObject(adapterId, ToByteString(byteString))
  }
}

@protocol object AnyProtocol extends Protocol {

  @Id(0x00aa) case class AnyObject(
    @Id(0x00ab) adapterId: Long,
    @Id(0x00ac) value: okio.ByteString
  )

}
