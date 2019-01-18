package ee.cone.c4actor

import java.util.UUID

import ee.cone.c4actor.ExternalProtocol.{CacheUpdate, ExternalUpdate}
import ee.cone.c4actor.QProtocol.{TxRef, Update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, Single, assemble}
import ee.cone.c4proto.{Id, Protocol, protocol}

import scala.collection.immutable.Seq

trait ExternalModelsApp {
  def external: List[Class[_ <: Product]] = Nil
}

case class ExternalOrig[Model <: Product](orig: Model)

trait ExternalUpdatesApp extends ProtocolsApp with UpdatesPreprocessorsApp with ExternalModelsApp {
  def toUpdate: ToUpdate
  def qAdapterRegistry: QAdapterRegistry

  override def protocols: List[Protocol] = ExternalProtocol :: super.protocols
  override def processors: List[UpdatesPreprocessor] = new ExternalUpdatesPreprocessor(toUpdate, qAdapterRegistry, external) :: super.processors
}

class ExternalUpdatesPreprocessor(toUpdate: ToUpdate, qAdapterRegistry: QAdapterRegistry, external: List[Class[_ <: Product]]) extends UpdatesPreprocessor {
  private val externalNames = external.map(_.getName).toSet
  private val externalIdSet: Set[Long] = qAdapterRegistry.byName.transform { case (_, v) ⇒ v.id }.filterKeys(externalNames).values.toSet

  def process(updates: Seq[Update]): Seq[Update] = {
    val external = updates.filter(u ⇒ externalIdSet(u.valueTypeId))
    if (external.isEmpty) Seq()
    else {
      val txRefId = UUID.randomUUID().toString
      (TxRef(txRefId, "") +: external.map(u ⇒ ExternalUpdate(u.srcId, u.valueTypeId, u.value, txRefId))).flatMap(LEvent.update).map(toUpdate.toUpdate)
    }
  }
}

@protocol(UpdatesCat) object ExternalProtocol extends Protocol {

  @Id(0x0080) case class ExternalUpdate(
    @Id(0x0081) srcId: String,
    @Id(0x0082) valueTypeId: Long,
    @Id(0x0083) value: okio.ByteString,
    @Id(0x0084) txRefId: String
  )

  @Id(0x0085) case class CacheUpdate(
    @Id(0x0086) srcId: String,
    @Id(0x0087) valueTypeId: Long,
    @Id(0x0088) value: okio.ByteString,
    @Id(0x0089) offset: String
  )

}
/*
@assemble class ExternalOrigJoiner[Model <: Product](modelCl: Class[Model], qAdapterRegistry: QAdapterRegistry) extends Assemble {
  def JoinOffset(
    origId: SrcId,

  )

  def CreateExternal(
    origId: SrcId,
    externals: Values[ExternalUpdate],
    caches: Values[CacheUpdate]
  ): Values[(SrcId, ExternalOrig[Model])] =
    (Single.option(externals), Single.option(caches)) match {

    }

}*/
