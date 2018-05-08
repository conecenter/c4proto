package ee.cone.c4actor

import ee.cone.c4actor.AnyProtocol.AnyObject
import ee.cone.c4proto.{Id, Protocol, ToByteString, protocol}

object AnyAdapter {
  def decode[Model <: Product](qAdapterRegistry: QAdapterRegistry): AnyObject ⇒ Model = any ⇒ {
    val adapter = qAdapterRegistry.byId(any.adapterId)
    adapter.decode(any.value).asInstanceOf[Model]
  }

  def decode[Model <: Product](qAdapterRegistry: QAdapterRegistry, modelCl: Class[Model]): AnyObject ⇒ Model = any ⇒ {
    val adapter = qAdapterRegistry.byId(any.adapterId)
    adapter.decode(any.value).asInstanceOf[Model]
  }

  def encode[Model <: Product](qAdapterRegistry: QAdapterRegistry): Model ⇒ AnyObject = model ⇒ {
    val adapter = qAdapterRegistry.byName(model.getClass.getName)
    val byteString = adapter.encode(model)
    val adapterId = adapter.id
    AnyObject(adapterId, ToByteString(byteString))
  }
}

@protocol object AnyProtocol extends Protocol{

  @Id(0x00aa) case class AnyObject(
    @Id(0x00ab) adapterId: Long,
    @Id(0x00ac) value: okio.ByteString
  )

}
