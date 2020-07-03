package ee.cone.c4actor

import ee.cone.c4assemble.Single
import ee.cone.c4di._

import scala.collection.immutable.Seq
import scala.concurrent.Promise

object EmptyDeferredSeq extends DeferredSeq[Nothing] {
  def value: Seq[Nothing] = Nil
}

/*
=> creators, templates
res_ioKeySet = 0
res_creators = 0
loop:
  res_creators += _new_creators
  _new_creators => new_ioKeySet (strip List)
  new_ioKeySet - res_ioKeySet => do_new_ioKeySet
  res_ioKeySet += do_new_ioKeySet
  do_new_ioKeySet =[templates]=> _new_creators
order creators
create components/reg
DeferredSetup

DeferredSeq[ComponentRegistry] ?

 */

@c4("BaseApp") final class ComponentRegistryImpl(app: AbstractComponents)(

){
  def setup() = {
    val appComponents = app.components
    val simpleCreators = appComponents.collect{ case c: ComponentCreator => c }
    val templates = appComponents.collect{ case c: ComponentCreatorTemplate => c }

  }

}



@c4("BaseApp") final class ComponentRegistryImpl(app: AbstractComponents)(
  debug: Boolean = Option(System.getenv("C4DEBUG_COMPONENTS")).nonEmpty
) extends ComponentRegistry {
  def toTypeKey[T](cl: Class[T], args: Seq[TypeKey]): TypeKey =
    CreateTypeKey(cl,cl.getSimpleName,args.toList)
  lazy val components: Seq[ComponentCreator] = fixNonFinal(app.components.map{ case c: ComponentCreator => c }.distinct)
  lazy val reg: Map[TypeKey,DeferredSeq[Object]] =
    components.map(toCached).groupBy(_.out)
      .transform((k,v)=>new SimpleDeferredSeq(()=>v.flatMap(_.deferredSeq.value)))
  // def toNonFinal(k: TypeKey): TypeKey = k.copy(alias = s"NonFinal#${k.alias}")
  def fixNonFinal(components: Seq[ComponentCreator]): Seq[ComponentCreator] = {
    if(debug) components.foreach(c=>println(s"component (out: ${c.out}) (in: ${c.in})"))
    val toNonFinal = components.flatMap(c => c.nonFinalOut.map(nOut=>c.out->nOut)).toMap
    components.map{ c =>
      if(c.nonFinalOut.nonEmpty) c
      else toNonFinal.get(c.out).fold(c)(nOut=>new ComponentCreator(nOut, c.nonFinalOut, c.in, c.create))
    }
  }
  class Cached(val out: TypeKey, val deferredSeq: DeferredSeq[Object])
  def toCached(component: ComponentCreator): Cached = {
    val values = if(ComponentRegistry.toRegistry(component).nonEmpty) ()=>Seq(this)
      else () => component.create(component.in.map(resolveSingle(component.out)))
    new Cached(component.out, new SimpleDeferredSeq[Object](values))
  }
  def resolveSingle: TypeKey => TypeKey => Object = outKey => inKey => resolveKey(inKey).value match {
    case Seq(r:Object) =>
      r
    case r => throw new Exception(s"resolution of $inKey for $outKey fails with $r")
  }
  def resolveKey(key: TypeKey): DeferredSeq[Any] =
    new SimpleDeferredSeq[Any](()=>
      if(!debug) resolveKeyDo(key) else try resolveKeyDo(key) catch{
        case e: StackOverflowError =>
          throw new ResolveKeyError(Set(key),s"${e.getMessage} with suggested dep loop:\n-- $key")
        case e: ResolveKeyError if e.keys.isEmpty || e.keys(key) =>
          throw new ResolveKeyError(Set.empty, e.message)
        case e: ResolveKeyError =>
          throw new ResolveKeyError(e.keys ++ Set(key),s"${e.message}\n-- $key")
      }
    )
  def resolveKeyDo(key: TypeKey): Seq[Any] = {
    val directRes: DeferredSeq[Any] = reg.getOrElse(key,EmptyDeferredSeq)
    val factoryKey = CreateTypeKey(classOf[ComponentFactory[_]],"ComponentFactory",List(key.copy(args=Nil)))
    val factories = reg.getOrElse(factoryKey,EmptyDeferredSeq).asInstanceOf[DeferredSeq[ComponentFactory[_]]]
    directRes.value ++ factories.value.flatMap(_(key.args))
  }
  def resolve[T](cl: Class[T], args: Seq[TypeKey]): DeferredSeq[T] =
    resolveKey(toTypeKey(cl,args)).asInstanceOf[DeferredSeq[T]]
}

class ResolveKeyError(
  val keys: Set[TypeKey], val message: String
) extends Exception(message)


@c4("BaseApp") final class OptionProvider {
  @provide def getOption[T](items: List[T]): Seq[Option[T]] =
    Seq(Single.option(items))
}

class DeferredSetup(val run: ()=>Unit)


abstract class SimpleDeferredSeq[T] extends DeferredSeq[T] {
  def set(value: Seq[T]): Unit
}
@c4("BaseApp") final class DeferredSeqProvider {
  @provide def getDeferredSeq[T](key: StrictTypeKey[T]): Seq[DeferredSeq[T]] = {
    val promise: Promise[Seq[T]] = Promise()
    val future = promise.future
    Seq(new SimpleDeferredSeq[T]{
      def value: Seq[T] = future.value.getOrElse(throw new Exception(s"can not access DeferredSeq at this stage: $key")).get
      def set(value: Seq[T]): Unit = promise.success(value)
    })
  }
  @provide def getDeferredSeqSetup[T](seq: DeferredSeq[T], list: List[T]): Seq[DeferredSetup] =
    Seq(new DeferredSetup(()=>seq.asInstanceOf[SimpleDeferredSeq[T]].set(list)))
}



@c4("BaseApp") final class DefComponentFactoryProvider(
  componentRegistry: ComponentRegistry
) {
  @provide def getList: Seq[ComponentFactory[List[_]]] =
    List(args=>Seq(componentRegistry.resolveKey(Single(args)).value.toList))
  @provide def getTypeKey: Seq[ComponentFactory[StrictTypeKey[_]]] =
    List(args=>Seq(StrictTypeKey(Single(args))))
}

