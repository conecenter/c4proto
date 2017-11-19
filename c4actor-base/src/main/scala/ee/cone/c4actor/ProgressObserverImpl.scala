package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging

@c4component case class ProgressObserverFactoryImpl(inner: RawObserverTreeFactory) extends ProgressObserverFactory {
  def create(endOffset: Long): RawObserver = new ProgressObserverImpl(inner.create(),endOffset)
}

class ProgressObserverImpl(inner: RawObserver, endOffset: Long, until: Long=0) extends RawObserver with LazyLogging {
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

@c4component case class CompletingRawObserverFactoryImpl(execution: Execution) extends CompletingRawObserverFactory {
  def create() = new CompletingRawObserver(execution)
}