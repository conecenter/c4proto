package ee.cone.c4actor

import ee.cone.c4actor.AnyOrigProtocol.N_AnyOrig
import ee.cone.c4proto._

object AnyAdapter {
  def decodeOpt[Model <: Product](qAdapterRegistry: QAdapterRegistry): N_AnyOrig => Option[Model] = any => {
    qAdapterRegistry.byId.get(any.adapterId).map(adapter =>
      adapter.decode(any.value).asInstanceOf[Model]
    )
  }

  def decode[Model <: Product](qAdapterRegistry: QAdapterRegistry): N_AnyOrig => Model = any => {
    val adapter = qAdapterRegistry.byId(any.adapterId)
    adapter.decode(any.value).asInstanceOf[Model]
  }

  def decodeProduct(qAdapterRegistry: QAdapterRegistry): N_AnyOrig => Product = any => {
    val adapter = qAdapterRegistry.byId(any.adapterId)
    adapter.decode(any.value)
  }

  def decode[Model <: Product](qAdapterRegistry: QAdapterRegistry, modelCl: Class[Model]): N_AnyOrig => Model = any => {
    val adapter = qAdapterRegistry.byId(any.adapterId)
    adapter.decode(any.value).asInstanceOf[Model]
  }

  def encode[Model <: Product](qAdapterRegistry: QAdapterRegistry): Model => N_AnyOrig = model => {
    val adapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(model.getClass.getName)
    val byteString = adapter.encode(model)
    val adapterId = adapter.id
    N_AnyOrig(adapterId, ToByteString(byteString))
  }

  def encode[Model <: Product](qAdapterRegistry: QAdapterRegistry, modelCl: Class[Model]): Model => N_AnyOrig = model => {
    val adapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(model.getClass.getName)
    val byteString = adapter.encode(model)
    val adapterId = adapter.id
    N_AnyOrig(adapterId, ToByteString(byteString))
  }
}

trait AnyOrigProtocolAppBase
@protocol("AnyOrigProtocolApp") object AnyOrigProtocol   {

  case class N_AnyOrig(
    @Id(0x00ab) adapterId: Long,
    @Id(0x00ac) value: okio.ByteString
  )

}
