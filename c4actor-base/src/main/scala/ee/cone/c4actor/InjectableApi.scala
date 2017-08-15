package ee.cone.c4actor

import ee.cone.c4assemble.Getter

trait ToInject {
  def toInject: List[Injectable]
}

class Injectable(val pair: (InjectableGetter[_],Any))

abstract class InjectableGetter[Item] extends Getter[Context,Item] {
  def of: Context ⇒ Item = context ⇒
    context.injected.getOrElse(this, throw new Exception(s"$this was not injected")).asInstanceOf[Item]
  def set: Item ⇒ List[Injectable] = item ⇒ List(new Injectable((this,item)))
}