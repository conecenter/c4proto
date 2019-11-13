package ee.cone.c4actor

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{MetaProp, c4}

@c4("RichDataCompApp") class ModelFactoryImpl(
  defaultModelInitializers: List[DefaultModelInitializer[_]],
  qAdapterRegistry: QAdapterRegistry,
  universalProtoAdapter: ProtoAdapter[UniversalNode],
  srcIdAdapter: ProtoAdapter[SrcId]
)(
  val reg: Map[String,DefaultModelInitializer[_]] =
    CheckedMap(defaultModelInitializers.map(f=>f.valueClass.getName->f))
) extends ModelFactory {
  def process[P<:Product](className: String, basedOn: Option[P], srcId: SrcId): P = {
    val adapter = qAdapterRegistry.byName(className)
    val node = makeUniversalNode(adapter.props.head, srcId)
    val patchArray: Array[Byte] = universalProtoAdapter.encode(node)
    val baseArray: Option[Array[Byte]] = basedOn.map(m => adapter.encode(m) ++ patchArray)
    val model = adapter.decode( baseArray.getOrElse(patchArray) ).asInstanceOf[P]
    reg.get(className).map(_.asInstanceOf[DefaultModelInitializer[P]].init(model)).getOrElse(model)
  }

  private def makeUniversalNode(headProp: MetaProp, srcId: SrcId): UniversalNode = {
    val headPropClass = headProp.typeProp.clName
    val propImpl = if (headPropClass == classOf[SrcId].getName) {
      UniversalPropImpl(headProp.id, srcId)(srcIdAdapter)
    } else {  //recursion
      // Dimik: not sure about overriding complex structures
      val chAdapter = qAdapterRegistry.byName(headPropClass)
      UniversalPropImpl(headProp.id, makeUniversalNode(chAdapter.props.head, srcId))(universalProtoAdapter)
    }
    UniversalNodeImpl(propImpl :: Nil)
  }
}
