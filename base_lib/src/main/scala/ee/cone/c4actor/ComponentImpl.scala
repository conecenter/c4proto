package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble.Single
import ee.cone.c4di.{AbstractComponents, Component, TypeKey, c4}

import scala.collection.immutable.Seq

class SimpleDeferredSeq[T](get: ()=>Seq[T]) extends DeferredSeq[T] {
  lazy val value: Seq[T] = get()
}
object EmptyDeferredSeq extends DeferredSeq[Nothing] {
  def value: Seq[Nothing] = Nil
}

@c4("BaseApp") class ComponentRegistryImpl(app: AbstractComponents) extends ComponentRegistry with LazyLogging {
  def toTypeKey[T](cl: Class[T], args: Seq[TypeKey]): TypeKey =
    TypeKey(cl.getName,cl.getSimpleName,args.toList)
  def general(key: TypeKey): TypeKey = key.copy(args=Nil) // key.args.map(_=>TypeKey("_"));   (1 to arity).map(_=>TypeKey("_","_",Nil)).toList
  lazy val reg: Map[TypeKey,DeferredSeq[Object]] =
    fixNonFinal(app.components.distinct).map(toCached).flatMap(generalize)
    .groupBy(_.out).transform((k,v)=>new SimpleDeferredSeq(()=>v.flatMap(_.deferredSeq.value)))
  // def toNonFinal(k: TypeKey): TypeKey = k.copy(alias = s"NonFinal#${k.alias}")
  def fixNonFinal(components: Seq[Component]): Seq[Component] = {
    Option(System.getenv("C4DEBUG_COMPONENTS")).foreach(_=>components.foreach(c=>println(s"component (out: ${c.out}) (in: ${c.in})")))
    val toNonFinal = components.flatMap(c => c.nonFinalOut.map(nOut=>c.out->nOut)).toMap
    components.map{ c =>
      if(c.nonFinalOut.nonEmpty) c
      else toNonFinal.get(c.out).fold(c)(nOut=>new Component(nOut, c.nonFinalOut, c.in, c.create))
    }
  }
  class Cached(val out: TypeKey, val deferredSeq: DeferredSeq[Object])
  def toCached(component: Component): Cached = {
    val values = if(ComponentRegistry.isRegistry(component)) ()=>Seq(this)
      else () => component.create(component.in.map(resolveSingle(component.out)))
    new Cached(component.out, new SimpleDeferredSeq[Object](values))
  }
  def resolveSingle: TypeKey => TypeKey => Object = outKey => inKey => resolveKey(inKey).value match {
    case Seq(r:Object) => r
    case r => throw new Exception(s"resolution of $inKey for $outKey fails with $r")
  }
  def generalize: Cached => Seq[Cached] = cached =>
    Seq(cached.out, general(cached.out)).distinct.map(o=>new Cached(o,cached.deferredSeq))
  def resolveKey(key: TypeKey): DeferredSeq[Any] = new SimpleDeferredSeq[Any](()=>{
    val directRes: DeferredSeq[Any] = reg.getOrElse(key,EmptyDeferredSeq)
    val factoryKey = toTypeKey(classOf[ComponentFactory[Object]],List(general(key)))
    val factories: DeferredSeq[Object] = reg.getOrElse(factoryKey,EmptyDeferredSeq)
    Option(System.getenv("C4DEBUG_COMPONENTS")).foreach(_=>println(s"resolveKey $key"))
    directRes.value ++
      factories.value.flatMap(f=>f.asInstanceOf[ComponentFactory[Object]].forTypes(key.args))
  })
  def resolve[T](cl: Class[T], args: Seq[TypeKey]): DeferredSeq[T] =
    resolveKey(toTypeKey(cl,args)).asInstanceOf[DeferredSeq[T]]
}

@c4("BaseApp") class SeqComponentFactory(
  componentRegistry: ComponentRegistry
) extends ComponentFactory[DeferredSeq[_]] {
  def forTypes(args: Seq[TypeKey]): Seq[DeferredSeq[_]] =
    Seq(componentRegistry.resolveKey(Single(args)))
}

@c4("BaseApp") class ListComponentFactory(
  componentRegistry: ComponentRegistry
) extends ComponentFactory[List[_]] {
  def forTypes(args: Seq[TypeKey]): Seq[List[_]] =
    Seq(componentRegistry.resolveKey(Single(args)).value.toList)
}

@c4("BaseApp") class OptionComponentFactory(
  componentRegistry: ComponentRegistry
) extends ComponentFactory[Option[_]] {
  def forTypes(args: Seq[TypeKey]): Seq[Option[_]] =
    Seq(Single.option(componentRegistry.resolveKey(Single(args)).value))
}