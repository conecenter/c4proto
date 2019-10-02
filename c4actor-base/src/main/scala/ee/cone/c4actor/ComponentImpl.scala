package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble.Single
import ee.cone.c4proto.{AbstractComponents, Component, TypeKey, c4component}

import scala.collection.immutable.Seq

class SimpleDeferredSeq[T](get: ()=>Seq[T]) extends DeferredSeq[T] {
  lazy val value: Seq[T] = get()
}
object EmptyDeferredSeq extends DeferredSeq[Nothing] {
  def value: Seq[Nothing] = Nil
}

@c4component("BaseApp") class ComponentRegistryImpl(app: AbstractComponents) extends ComponentRegistry with LazyLogging {
  import ComponentRegistry.toTypeKey
  def general(key: TypeKey): TypeKey = key.copy(args=Nil) // key.args.map(_=>TypeKey("_"));   (1 to arity).map(_=>TypeKey("_","_",Nil)).toList
  lazy val reg: Map[TypeKey,DeferredSeq[Object]] =
    fixNonFinal(app.components.distinct).flatMap(toCached).flatMap(generalize)
      .groupBy(_.out).transform((k,v)=>new SimpleDeferredSeq(()=>v.flatMap(_.deferredSeq.value)))
  def finalSet(c: Component): Set[TypeKey] = c.in.intersect(c.out).toSet
  def toNonFinal(k: TypeKey): TypeKey = k.copy(alias = s"NonFinal#${k.alias}")
  def fixNonFinal(components: Seq[Component]): Seq[Component] = {
    val allFinal = components.flatMap(finalSet)
    components.map(c =>
      if(!c.out.exists(allFinal.contains)) c else {
        val thisFinal = finalSet(c)
        val nIn = c.in.map(k => if(thisFinal.contains(k)) toNonFinal(k) else k)
        val nOut = c.out.map(k => if(allFinal.contains(k) && !thisFinal.contains(k)) toNonFinal(k) else k)
        new Component(nOut, nIn, c.create)
      }
    )
  }
  class Cached(val out: TypeKey, val deferredSeq: DeferredSeq[Object])
  def toCached(component: Component): Seq[Cached] = {
    lazy val values = component.create(component.in.map(resolveSingle))
    val deferredSeq = new SimpleDeferredSeq[Object](if(ComponentRegistry.isRegistry(component)) ()=>Seq(this) else ()=>values)
    component.out.map(new Cached(_, deferredSeq))
  }
  def resolveSingle(key: TypeKey): Object = resolveKey(key).value match {
    case Seq(r:Object) => r
    case r => throw new Exception(s"resolution of $key fails with $r")
  }
  def generalize: Cached => Seq[Cached] = cached =>
    Seq(cached.out, general(cached.out)).distinct.map(o=>new Cached(o,cached.deferredSeq))
  def resolveKey(key: TypeKey): DeferredSeq[Any] = new SimpleDeferredSeq[Any](()=>{
    val directRes: DeferredSeq[Any] = reg.getOrElse(key,EmptyDeferredSeq)
    val factoryKey = toTypeKey(classOf[ComponentFactory[Object]],List(general(key)))
    val factories: DeferredSeq[Object] = reg.getOrElse(factoryKey,EmptyDeferredSeq)
    logger.debug(s"$key")
    directRes.value ++
      factories.value.flatMap(f=>f.asInstanceOf[ComponentFactory[Object]].forTypes(key.args))
  })
  def resolve[T](cl: Class[T], args: Seq[TypeKey]): DeferredSeq[T] =
    resolveKey(toTypeKey(cl,args)).asInstanceOf[DeferredSeq[T]]
  def resolveSingle[T](cl: Class[T]): T =
    resolveSingle(toTypeKey(cl,Nil)).asInstanceOf[T]
}

@c4component("BaseApp") class SeqComponentFactory(
  componentRegistry: ComponentRegistry
) extends ComponentFactory[DeferredSeq[_]] {
  def forTypes(args: Seq[TypeKey]): Seq[DeferredSeq[_]] =
    Seq(componentRegistry.resolveKey(Single(args)))
}

@c4component("BaseApp") class ListComponentFactory(
  componentRegistry: ComponentRegistry
) extends ComponentFactory[List[_]] {
  def forTypes(args: Seq[TypeKey]): Seq[List[_]] =
    Seq(componentRegistry.resolveKey(Single(args)).value.toList)
}

@c4component("BaseApp") class OptionComponentFactory(
  componentRegistry: ComponentRegistry
) extends ComponentFactory[Option[_]] {
  def forTypes(args: Seq[TypeKey]): Seq[Option[_]] =
    Seq(Single.option(componentRegistry.resolveKey(Single(args)).value))
}