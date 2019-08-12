package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_FailedUpdates

import scala.annotation.tailrec

class RootConsumer(
  reducer: RichRawWorldReducer,
  snapshotMaker: SnapshotMaker,
  loader: SnapshotLoader,
  progressObserverFactory: ProgressObserverFactory,
  consuming: Consuming
) extends Executable with LazyLogging {
  def run(): Unit = concurrent.blocking { //ck mg
    logger.info(s"Starting RootConsumer...")
    GCLog("before loadRecent")
    val initialRawWorld: RichContext =
      (for{
        snapshot ← {
          logger.debug("Making snapshot")
          snapshotMaker.make(NextSnapshotTask(None)).toStream
        }
        event ← {
          logger.debug(s"Loading $snapshot")
          loader.load(snapshot)
        }
        world ← {
          logger.debug(s"Reducing $snapshot")
          Option(reducer.reduce(None,List(event)))
        }
        if ByPK(classOf[S_FailedUpdates]).of(world).isEmpty
      } yield {
        logger.info(s"Snapshot reduced without failures [${snapshot.relativePath}]")
        world
      }).head
    GCLog("after loadRecent")
    consuming.process(initialRawWorld.offset, consumer ⇒ {
      val initialRawObserver = progressObserverFactory.create(consumer.endOffset)
      iteration(consumer, initialRawWorld, initialRawObserver)
    })
  }
  @tailrec private def iteration(
    consumer: Consumer, world: RichContext, observer: RawObserver
  ): Unit = if(!observer.isInstanceOf[FinishedRawObserver]){
    val events = consumer.poll()
    if(events.nonEmpty){
      val latency = System.currentTimeMillis-events.map{ case e: MTime ⇒ e.mTime}.min //check rec.timestampType == TimestampType.CREATE_TIME ?
      logger.debug(s"p-c latency $latency ms")
    }
    val end = NanoTimer()
    val newWorld = reducer.reduce(Option(world),events)
    val period = end.ms
    if(events.nonEmpty)
      logger.debug(s"reduced ${events.size} tx-s in $period ms")
    val newObserver = observer.activate(newWorld)
    //GCLog("iteration done")
    iteration(consumer, newWorld, newObserver)
  }
}

object GCLog extends LazyLogging {
  def apply(hint: String): Unit = {
    System.gc()
    val runtime = Runtime.getRuntime
    val used = runtime.totalMemory - runtime.freeMemory
    logger.info(s"$hint: then $used bytes used")
  }
}
