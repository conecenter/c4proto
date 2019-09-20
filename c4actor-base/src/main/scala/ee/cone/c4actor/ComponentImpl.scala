package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble.Single
import ee.cone.c4proto.{AbstractComponents, Component, TypeKey, c4component}

import scala.collection.immutable.Seq

class AppSeq[T](inner: Seq[()⇒T]) extends Seq[T] {
  def length: Int = inner.length
  def apply(idx: Int): T = inner(idx)()
  def iterator: Iterator[T] = inner.iterator.map(_())
}

@c4component class ComponentRegistryImpl(app: AbstractComponents) extends ComponentRegistry with LazyLogging {
  def general(key: TypeKey): TypeKey = key.copy(args=Nil) // key.args.map(_⇒TypeKey("_"));   (1 to arity).map(_⇒TypeKey("_","_",Nil)).toList
  lazy val reg: Map[TypeKey,Seq[Object]] =
    fixNonFinal(app.components.distinct).map(toCached).flatMap(generalize)
      .groupBy(_.out).transform((k,v)⇒new AppSeq(v.map(_.get)))
  def isFinal(c: Component): Boolean = c.in.contains(c.out)
  def toNonFinal(component: Component): TypeKey⇒TypeKey = key ⇒
    if(key==component.out) key.copy(alias = s"NonFinal#${key.alias}") else key
  def fixNonFinal(components: Seq[Component]): Seq[Component] = {
    val outs = components.collect{ case c if isFinal(c) ⇒ c.out }.toSet
    components.map(c ⇒
      if(!outs(c.out)) c
      else if(isFinal(c)) new Component(c.out,c.in.map(toNonFinal(c)),c.create)
      else new Component(toNonFinal(c)(c.out),c.in,c.create)
    )
  }
  class Cached(val out: TypeKey, val get: ()⇒Object)
  def toCached(component: Component): Cached = {
    lazy val value = component.create(component.in.map(resolveSingle))
    val get = if(ComponentRegistry.isRegistry(component)) ()⇒this else ()⇒value
    new Cached(component.out, get)
  }
  def resolveSingle(key: TypeKey): Object = resolveKey(key) match {
    case Seq(r:Object) ⇒ r
    case r ⇒ throw new Exception(s"resolution of $key fails with $r")
  }
  def generalize: Cached ⇒ Seq[Cached] = cached ⇒
    Seq(cached.out, general(cached.out)).distinct.map(o⇒new Cached(o,cached.get))
  def resolveKey(key: TypeKey): Seq[Any] = {
    val directRes = reg.getOrElse(key,Nil)
    val factories = reg.getOrElse(toTypeKey(classOf[ComponentFactory[Object]],List(general(key))),Nil)
    val dynamicRes = factories.flatMap(f⇒f.asInstanceOf[ComponentFactory[Object]].forTypes(key.args))
    logger.debug(s"${directRes.size} ${dynamicRes.size} $key")
    directRes ++ dynamicRes
  }
  def toTypeKey[T](cl: Class[T], args: Seq[TypeKey]): TypeKey =
    TypeKey(cl.getName,cl.getSimpleName,args.toList)
  def resolve[T](cl: Class[T], args: Seq[TypeKey]): Seq[T] =
    resolveKey(toTypeKey(cl,args)).asInstanceOf[Seq[T]]
}

@c4component class SeqComponentFactory(
  componentRegistry: ComponentRegistry
) extends ComponentFactory[Seq[_]] {
  def forTypes(args: Seq[TypeKey]): Seq[Seq[_]] =
    Seq(componentRegistry.resolveKey(Single(args)))
}
