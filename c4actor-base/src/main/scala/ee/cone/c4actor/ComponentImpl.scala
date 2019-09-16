package ee.cone.c4actor

import ee.cone.c4proto.{AbstractComponents, TypeKey, c4component}

import scala.collection.immutable.Seq

class AppSeq[T](inner: Seq[()⇒T]) extends Seq[T] {
  def length: Int = inner.length
  def apply(idx: Int): T = inner(idx)()
  def iterator: Iterator[T] = inner.iterator.map(_())
}

@c4component case class ComponentRegistryImpl(app: AbstractComponents) extends ComponentRegistry {
  def general(key: TypeKey): TypeKey = key.copy(args=key.args.map(_⇒TypeKey("_")))
  lazy val reg: Map[TypeKey,Seq[Product]] =
    app.components.distinct.flatMap{ component ⇒
      lazy val value = if(ComponentRegistry.isRegistry(component)) this
      else component.create(component.in.map(k⇒resolveSingle(k).asInstanceOf[Object]))
      Seq(component.out, general(component.out)).distinct.map(o⇒o→(()⇒value))
    }.groupBy(_._1).transform((k,v)⇒new AppSeq(v.map(_._2)))

  def resolve(key: TypeKey): Seq[Any] = reg.getOrElse(key,Nil) ++
    reg.getOrElse(TypeKey(classOf[ComponentFactory[Product]].getName,Seq(general(key))),Nil)
      .flatMap(f⇒f.asInstanceOf[ComponentFactory[Product]].forTypes(key.args))
  def resolveSingle(key: TypeKey): Any = resolve(key) match {
    case Seq(r) ⇒ r
    case r ⇒ throw new Exception(s"resolution of $key fails with $r")
  }
  def resolveSingle[T](cl: Class[T]): T =
    resolveSingle(TypeKey(cl.getName)).asInstanceOf[T]
}
