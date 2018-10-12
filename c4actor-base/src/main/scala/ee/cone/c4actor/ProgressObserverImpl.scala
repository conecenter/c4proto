package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.NextOffset

class ProgressObserverFactoryImpl(inner: RawObserver) extends ProgressObserverFactory {
  def create(endOffset: NextOffset): RawObserver = new ProgressObserverImpl(inner,endOffset)
}

class ProgressObserverImpl(inner: RawObserver, endOffset: NextOffset, until: Long=0) extends RawObserver with LazyLogging {
  def activate(rawWorld: RawWorld): RawObserver =
    if (rawWorld.offset < endOffset) {
      val now = System.currentTimeMillis
      if(now < until) this else {
        logger.debug(s"loaded ${rawWorld.offset}/$endOffset")
        new ProgressObserverImpl(inner, endOffset, now+1000)
      }
    } else inner.activate(rawWorld)
}

class CompletingRawObserver(execution: Execution) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = {
    execution.complete()
    CompletedRawObserver
  }
}

object CompletedRawObserver extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = this
}
