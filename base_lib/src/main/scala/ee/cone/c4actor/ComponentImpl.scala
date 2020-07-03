package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble.Single
import ee.cone.c4di._
import ee.cone.c4di.Types._

import scala.collection.immutable.Seq

class SimpleDeferredSeq[T](get: ()=>Seq[T]) extends DeferredSeq[T] {
  lazy val value: Seq[T] = get()
}
object EmptyDeferredSeq extends DeferredSeq[Nothing] {
  def value: Seq[Nothing] = Nil
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

@c4("BaseApp") final class DefComponentFactoryProvider(
  componentRegistry: ComponentRegistry
) {
  @provide def getDeferredSeq: Seq[ComponentFactory[DeferredSeq[_]]] =
    List(args=>Seq(componentRegistry.resolveKey(Single(args))))
  @provide def getList: Seq[ComponentFactory[List[_]]] =
    List(args=>Seq(componentRegistry.resolveKey(Single(args)).value.toList))
  @provide def getOption: Seq[ComponentFactory[Option[_]]] =
    List(args=>Seq(Single.option(componentRegistry.resolveKey(Single(args)).value)))
  @provide def getTypeKey: Seq[ComponentFactory[StrictTypeKey[_]]] =
    List(args=>Seq(StrictTypeKey(Single(args))))
}
