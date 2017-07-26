package ee.cone.c4actor

class ProgressObserverFactoryImpl(inner: RawObserver) extends ProgressObserverFactory {
  def create(endOffset: Long): RawObserver = new ProgressObserverImpl(inner,endOffset)
}

class ProgressObserverImpl(inner: RawObserver, endOffset: Long, until: Long=0) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver =
    if (rawWorld.offset < endOffset) {
      val now = System.currentTimeMillis
      if(now < until) this else {
        println(s"loaded ${rawWorld.offset}/$endOffset")
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
