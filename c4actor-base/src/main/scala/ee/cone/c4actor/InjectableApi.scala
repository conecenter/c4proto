package ee.cone.c4actor

import ee.cone.c4assemble.Getter

trait ToInject {
  def toInject: List[Injectable]
}

class Injectable(val pair: (SharedComponentKey[_],Object))

trait InjectableGetter[C,I] extends Getter[C,I] {
  def set: I ⇒ List[Injectable]
}

abstract class SharedComponentKey[Item<:Object] extends InjectableGetter[Context,Item] {
  def of: Context ⇒ Item = context ⇒
    context.injected.getOrElse(this, throw new Exception(s"$this was not injected")).asInstanceOf[Item]
  def set: Item ⇒ List[Injectable] = item ⇒ List(new Injectable((this,item)))
}