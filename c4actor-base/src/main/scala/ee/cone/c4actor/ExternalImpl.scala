package ee.cone.c4actor

import java.util.UUID

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.ExternalProtocol.{CacheUpdate, ExternalUpdate}
import ee.cone.c4actor.QProtocol.{TxRef, Update}
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, Single, assemble, by}
import ee.cone.c4proto.{HasId, Id, Protocol, protocol}
import okio.ByteString

import scala.collection.immutable.Seq

trait ExternalModelsApp {
  def external: List[Class[_ <: Product]] = Nil
}

trait ExternalUpdatesApp extends ProtocolsApp with UpdatesPreprocessorsApp with ExternalModelsApp with AssemblesApp {
  def toUpdate: ToUpdate
  def qAdapterRegistry: QAdapterRegistry

  override def protocols: List[Protocol] = ExternalProtocol :: super.protocols
  override def processors: List[UpdatesPreprocessor] = new ExternalUpdatesPreprocessor(toUpdate, qAdapterRegistry, external) :: super.processors
  override def assembles: List[Assemble] = external.map(ext ⇒ {
    val extName = ext.getName
    val id = qAdapterRegistry.byName(extName).id
    new ExternalOrigJoiner(ext, id, qAdapterRegistry)()
  }

  ) ::: super.assembles
}

class ExternalUpdatesPreprocessor(toUpdate: ToUpdate, qAdapterRegistry: QAdapterRegistry, external: List[Class[_ <: Product]]) extends UpdatesPreprocessor {
  private val externalNames = external.map(_.getName).toSet
  private val externalIdSet: Set[Long] = qAdapterRegistry.byName.transform { case (_, v) ⇒ v.id }.filterKeys(externalNames).values.toSet

  def process(updates: Seq[Update]): Seq[Update] = {
    val external = updates.filter(u ⇒ externalIdSet(u.valueTypeId))
    if (external.isEmpty) Seq()
    else {
      val txRefId = UUID.randomUUID().toString
      (TxRef(txRefId, "") +: external.map(u ⇒ ExternalUpdate(u.srcId + "0x%04x".format(u.valueTypeId), u.srcId, u.valueTypeId, u.value, txRefId))).flatMap(LEvent.update).map(toUpdate.toUpdate)
    }
  }
}

@protocol(UpdatesCat) object ExternalProtocol extends Protocol {

  @Id(0x0080) case class ExternalUpdate(
    @Id(0x008a) srcId: String,
    @Id(0x0081) origSrcId: String,
    @Id(0x0082) origTypeId: Long,
    @Id(0x0083) origValue: okio.ByteString,
    @Id(0x0084) txRefId: String
  )

  @Id(0x0085) case class CacheUpdate(
    @Id(0x008b) srcId: String,
    @Id(0x0086) origSrcId: String,
    @Id(0x0087) origTypeId: Long,
    @Id(0x0088) origValue: okio.ByteString,
    @Id(0x0089) offset: String
  )

}

case class ExternalRichUpdate[Model <: Product](externalUpdate: ExternalUpdate, txRef: TxRef) {
  def offset: NextOffset = txRef.txId
  def origValue: ByteString = externalUpdate.origValue
}

trait ExternalUpdateUtil[Model <: Product] {
  type TxRefId[ModelType] = SrcId
}

@assemble class ExternalOrigJoiner[Model <: Product](
  modelCl: Class[Model],
  modelId: Long,
  qAdapterRegistry: QAdapterRegistry
)(
  adapter: ProtoAdapter[Model] with HasId = qAdapterRegistry.byId(modelId).asInstanceOf[ProtoAdapter[Model] with HasId]
) extends Assemble with ExternalUpdateUtil[Model] {

  def ToOffset(
    origId: SrcId,
    extU: Each[ExternalUpdate]
  ): Values[(TxRefId[Model], ExternalUpdate)] =
    if (extU.origTypeId == modelId)
      List(extU.txRefId → extU)
    else Nil

  def ToExternalRichUpdate(
    txRefId: SrcId,
    txRef: Each[TxRef],
    @by[TxRefId[Model]] extU: Each[ExternalUpdate]
  ): Values[(SrcId, ExternalRichUpdate[Model])] =
    List(WithPK(ExternalRichUpdate[Model](extU, txRef)))


  def CreateExternal(
    origId: SrcId,
    externals: Values[ExternalRichUpdate[Model]],
    caches: Values[CacheUpdate]
  ): Values[(SrcId, Model)] =
    (Single.option(externals), Single.option(caches)) match {
      case (Some(e), None) ⇒ List(WithPK(adapter.decode(e.origValue)))
      case (None, Some(c)) ⇒ List(WithPK(adapter.decode(c.origValue)))
      case (Some(e), Some(c)) ⇒
        if (e.offset > c.offset)
          List(WithPK(adapter.decode(e.origValue)))
        else
          List(WithPK(adapter.decode(c.origValue)))
      case _ ⇒ Nil
    }

}
