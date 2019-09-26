package ee.cone.c4actor

import ee.cone.c4proto.{AbstractComponents, Component, TypeKey}
import ee.cone.c4assemble.Single

import scala.collection.immutable.Seq

object ComponentRegistry {
  def isRegistry: Component=>Boolean = {
    val clName = classOf[ComponentRegistry].getName
    c => c.out.exists(out => out.clName == clName)
  }
  def apply(app: AbstractComponents): ComponentRegistry =
    Single(Single(app.components.filter(isRegistry).distinct).create(Seq(app)))
      .asInstanceOf[ComponentRegistry]
  def toTypeKey[T](cl: Class[T], args: Seq[TypeKey]): TypeKey =
    TypeKey(cl.getName,cl.getSimpleName,args.toList)
  def provide[T<:Object](cl: Class[T], get: ()=>Seq[T]): Component =
    new Component(Seq(toTypeKey(cl,Nil)),Nil,_=>get())
}

trait ComponentRegistry {
  def resolveKey(key: TypeKey): DeferredSeq[Any]
  def resolve[T](cl: Class[T], args: Seq[TypeKey]): DeferredSeq[T]
  def resolveSingle[T](cl: Class[T]): T
}

abstract class ComponentFactory[T] {
  def forTypes(args: Seq[TypeKey]): Seq[T]
}

trait DeferredSeq[+T] {
  def value: Seq[T]
}