package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4proto.c4

@c4("ServerCompApp") class ProgressObserverFactoryImpl(inner: TxObserver) extends ProgressObserverFactory {
  def create(endOffset: NextOffset): Observer[RichContext] = new ProgressObserverImpl(inner.value,endOffset)
}

class ProgressObserverImpl(inner: Observer[RichContext], endOffset: NextOffset, until: Long=0) extends Observer[RichContext] with LazyLogging {
  def activate(rawWorld: RichContext): Observer[RichContext] =
    if (rawWorld.offset < endOffset) {
      val now = System.currentTimeMillis
      if(now < until) this else {
        logger.debug(s"loaded ${rawWorld.offset}/$endOffset")
        new ProgressObserverImpl(inner, endOffset, now+1000)
      }
    } else {
      logger.info(s"Stats OK -- loaded ALL/$endOffset")
      inner.activate(rawWorld)
    }
}
