package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4di.c4
import ee.cone.c4proto._

@c4("RichDataCompApp") final class ModelFactoryImpl(
  defaultModelInitializers: List[GeneralDefaultModelInitializer],
  qAdapterRegistry: QAdapterRegistry,
  universalProtoAdapter: ProtoAdapter[UniversalNode],
  srcIdAdapter: ProtoAdapter[SrcId],
  universalNodeFactory: UniversalNodeFactory
)(
  val reg: Map[String,GeneralDefaultModelInitializer] =
    CheckedMap(defaultModelInitializers.map(f=>f.valueClass.getName->f))
) extends ModelFactory {
  import universalNodeFactory._
  def process[P<:Product](className: String, basedOn: Option[P], srcId: SrcId): P = {
    val adapter = qAdapterRegistry.byName(className)
    val node = makeUniversalNode(adapter.props.head, srcId)
    val patchArray: Array[Byte] = universalProtoAdapter.encode(node)
    val baseArray: Option[Array[Byte]] = basedOn.map(m => adapter.encode(m) ++ patchArray)
    val model = adapter.decode( baseArray.getOrElse(patchArray) ).asInstanceOf[P]
    reg.get(className).fold(model)(_.init(model))
  }

  private def makeUniversalNode(headProp: MetaProp, srcId: SrcId): UniversalNode = {
    val headPropClass = headProp.typeProp.clName
    val propImpl = if (headPropClass == classOf[SrcId].getName) {
      prop(headProp.id, srcId, srcIdAdapter)
    } else {  //recursion
      // Dimik: not sure about overriding complex structures
      val chAdapter = qAdapterRegistry.byName(headPropClass)
      prop(headProp.id, makeUniversalNode(chAdapter.props.head, srcId), universalProtoAdapter)
    }
    node(propImpl :: Nil)
  }
}
