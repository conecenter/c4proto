package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble.StartUpSpaceProfiler
import ee.cone.c4di.c4
import java.lang.management.ManagementFactory
import scala.annotation.tailrec

@c4("ServerCompApp") final class RootConsumer(
  reducer: RichRawWorldReducer,
  snapshotLast: SnapshotLast,
  loader: SnapshotLoader,
  progressObserverFactory: ProgressObserverFactory,
  consuming: Consuming,
  startUpSpaceProfiler: StartUpSpaceProfiler,
  snapshotCheckReset: SnapshotCheckReset,
) extends Executable with Early with LazyLogging {
  def run(): Unit = concurrent.blocking { //ck mg
    logger.info(s"Starting RootConsumer...")
    snapshotCheckReset.run()
    GCLog("before loadRecent")
    val snapshot = snapshotLast.get
    logger.debug(s"Loading $snapshot")
    val events = snapshot.map(loader.load(_).get)
    val rt = ManagementFactory.getRuntimeMXBean
    logger.info(s"Reducing $snapshot -- uptime ${rt.getUptime}ms")
    val world = reducer.createContext(events)
    logger.info(s"Snapshot reduced without failures $snapshot -- uptime ${rt.getUptime}ms")
    GCLog("after loadRecent")
    startUpSpaceProfiler.out(world.assembled)
    consuming.process(world.offset, consumer => {
      val endOffset = consumer.endOffset
      assert(world.offset <= endOffset, s"bad consumer end offset $endOffset")
      val initialRawObserver = progressObserverFactory.create(endOffset)
      iteration(consumer, world, initialRawObserver)
    })
  }
  @tailrec private def iteration(
    consumer: Consumer, world: RichContext, observer: Observer[RichContext]
  ): Unit = {
    val events = consumer.poll()
    val end = NanoTimer()
    val newWorld = reducer.reduce(world, events)
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

