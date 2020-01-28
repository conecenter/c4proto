package ee.cone.c4actor

import ee.cone.c4actor.Types.{ClName, SrcId}
import ee.cone.c4assemble.Types.{Index, Values}
import ee.cone.c4assemble.{AssembledKey, Getter, IndexUtil, Single, Types}
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




@c4("RichDataCompApp") class JoinKeyComponentFactoryProvider(
  //componentRegistry: ComponentRegistry
  indexUtil: IndexUtil,
  optionClName: String = classOf[Option[_]].getName,
  valuesClName: String = classOf[Values[_]].getName,
) {
  @provide def get: Seq[ComponentFactory[ByFK[_,_]]] = List(args=>{
    val Seq(kTypeKey,vTypeKey) = args
    val toRes: Values[_]=>Any = vTypeKey.clName match {
      case `valuesClName` => a=>a
      case `optionClName` => a=>Single.option(a)
    }
    val vInnerTypeKey = Single(vTypeKey.args)
    assert(kTypeKey.args.isEmpty && vInnerTypeKey.args.isEmpty)
    // ?todo: assert OR alias and clName for joinKey should be extended by args
    val joinKey = indexUtil.joinKey(was = false, kTypeKey.alias, kTypeKey.clName, vInnerTypeKey.clName)
    List(new ByFK[Nothing,Any] {
      def ofA(context: AssembledContext): Nothing=>Any = {
        val index: Index = joinKey.of(context.assembled).value.get.get
        key => toRes(indexUtil.getValues(index,key,""))
      }
    })
  })
}


// bars: ByFK[SrcId,Each[Bar]]
// bars.of(local).get(k)