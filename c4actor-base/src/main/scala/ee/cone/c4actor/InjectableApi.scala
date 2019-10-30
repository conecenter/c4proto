package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble.Getter

trait ToInject {
  def toInject: List[Injectable]
}

class Injectable(val pair: (SharedComponentKey[_],Object))

trait InjectableGetter[C,I] extends Getter[C,I] {
  def set: I => List[Injectable]
}

abstract class SharedComponentKey[D_Item<:Object] extends InjectableGetter[SharedContext,D_Item] with LazyLogging {
  def of: SharedContext => D_Item = context =>
    context.injected.getOrElse(this, throw new Exception(s"$this was not injected")).asInstanceOf[D_Item]
  def set: D_Item => List[Injectable] = item => {
    logger.debug(s"injecting ${getClass.getName}")
    List(new Injectable((this,item)))
  }
}