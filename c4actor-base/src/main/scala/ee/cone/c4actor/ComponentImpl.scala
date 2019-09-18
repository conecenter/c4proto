package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble.Single
import ee.cone.c4proto.{AbstractComponents, TypeKey, c4component}

import scala.collection.immutable.Seq

class AppSeq[T](inner: Seq[()⇒T]) extends Seq[T] {
  def length: Int = inner.length
  def apply(idx: Int): T = inner(idx)()
  def iterator: Iterator[T] = inner.iterator.map(_())
}

@c4component class ComponentRegistryImpl(app: AbstractComponents) extends ComponentRegistry with LazyLogging {
  def general(key: TypeKey): TypeKey = key.copy(args=Nil) // key.args.map(_⇒TypeKey("_"));   (1 to arity).map(_⇒TypeKey("_","_",Nil)).toList
  lazy val reg: Map[TypeKey,Seq[Object]] =
    app.components.distinct.flatMap{ component ⇒
      lazy val value = if(ComponentRegistry.isRegistry(component)) this
      else component.create(component.in.map(k⇒resolveSingle(k).asInstanceOf[Object]))
      Seq(component.out, general(component.out)).distinct.map(o⇒o→(()⇒value))
    }.groupBy(_._1).transform((k,v)⇒new AppSeq(v.map(_._2)))

  def resolveKey(key: TypeKey): Seq[Any] = {
    val directRes = reg.getOrElse(key,Nil)
    val factories = reg.getOrElse(toTypeKey(classOf[ComponentFactory[Object]],List(general(key))),Nil)
    val dynamicRes = factories.flatMap(f⇒f.asInstanceOf[ComponentFactory[Object]].forTypes(key.args))
    logger.debug(s"${directRes.size} ${dynamicRes.size} $key")
    directRes ++ dynamicRes
  }
  def resolveSingle(key: TypeKey): Any = resolveKey(key) match {
    case Seq(r) ⇒ r
    case r ⇒ throw new Exception(s"resolution of $key fails with $r")
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
