package ee.cone.c4actor

import ee.cone.c4actor.Types.{ClName, SrcId}
import ee.cone.c4assemble.{AssembledKey, Getter, IndexUtil}
import ee.cone.c4di.Types.ComponentFactory
import ee.cone.c4di.{c4, provide}

@c4("RichDataCompApp") class SwitchOrigKeyFactoryHolder(proposition: Option[OrigKeyFactoryProposition], byPKKeyFactory: KeyFactory)
  extends OrigKeyFactoryFinalHolder(proposition.fold(byPKKeyFactory)(_.value))

@c4("RichDataCompApp") case class DefaultKeyFactory(composes: IndexUtil)(
  srcIdAlias: String = "SrcId",
  srcIdClass: ClName = classOf[SrcId].getName
) extends KeyFactory {
  def rawKey(className: String): AssembledKey =
    composes.joinKey(was = false, srcIdAlias, srcIdClass, className)
}


trait ByFK[K,V] extends Getter[SharedContext with AssembledContext,Map[K,V]]

@c4("RichDataCompApp") class JoinKeyComponentFactoryProvider(
  //componentRegistry: ComponentRegistry
  composes: IndexUtil
) {
  @provide def get: Seq[ComponentFactory[ByFK[_,_]]] = List(args=>{
    val Seq(kTypeKey,vTypeKey) = args
    assert(kTypeKey.args.isEmpty && vTypeKey.args.isEmpty)
    // ?todo: assert OR alias and clName for joinKey should be extended by args
    val key = composes.joinKey(was = false, kTypeKey.alias, kTypeKey.clName, vTypeKey.clName)
    List(new ByFK[_,Product] {
      def of: SharedContext with AssembledContext => Map[_,Product] = context => {
        val index = key.of(context.assembled).value.get.get
        UniqueIndexMap(index)(composes) // ?? Each[_]
      }
    })
  })
}