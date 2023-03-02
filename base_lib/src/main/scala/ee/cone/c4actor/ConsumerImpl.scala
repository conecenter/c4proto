package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_FailedUpdates
import ee.cone.c4assemble.StartUpSpaceProfiler
import ee.cone.c4di.c4

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets.UTF_8
import scala.annotation.tailrec

object StartUpSnapshotUtil {
  def path(config: Config): Path = Paths.get("/tmp/c4snapshot-"+config.get("C4PARENT_PID"))
}

@c4("StartUpSnapshotApp") final class StartUpSnapshotRequestSender(
  snapshotMaker: SnapshotMaker, config: Config
) extends Executable with Early with LazyLogging {
  private def ignoreTheSamePath(path: Path): Unit = ()
  private def write(content: String): Unit =
    ignoreTheSamePath(Files.write(StartUpSnapshotUtil.path(config), content.getBytes(UTF_8)))
  def run(): Unit = {
    logger.info("Making snapshot start")
    write("")
    write(snapshotMaker.make(NextSnapshotTask(None)).head.relativePath)
    logger.info("Making snapshot end")
  }
}

@c4("ServerCompApp") final class RootConsumer(
  reducer: RichRawWorldReducer,
  snapshotMaker: SnapshotMaker,
  loader: SnapshotLoader,
  progressObserverFactory: ProgressObserverFactory,
  consuming: Consuming,
  getS_FailedUpdates: GetByPK[S_FailedUpdates],
  startUpSpaceProfiler: StartUpSpaceProfiler,
  config: Config,
) extends Executable with Early with LazyLogging {
  //noinspection AccessorLikeMethodIsEmptyParen
  @tailrec private def getSnapshot(): RawSnapshot = {
    val path = StartUpSnapshotUtil.path(config)
    if(!Files.exists(path)){
      logger.debug("Making snapshot")
      snapshotMaker.make(NextSnapshotTask(None)).head
    } else {
      val content = new String(Files.readAllBytes(path), UTF_8)
      if (content.nonEmpty) {
        logger.debug("Snapshot found")
        RawSnapshot(content)
      } else {
        logger.debug("Waiting for snapshot")
        Thread.sleep(1000)
        getSnapshot()
      }
    }
  }

  def run(): Unit = concurrent.blocking { //ck mg
    logger.info(s"Starting RootConsumer...")
    GCLog("before loadRecent")
    val snapshot = getSnapshot()
    logger.debug(s"Loading $snapshot")
    val Some(event) = loader.load(snapshot)
    logger.debug(s"Reducing $snapshot")
    val world = reducer.reduce(None,List(event))
    if(getS_FailedUpdates.ofA(world).nonEmpty) throw new Exception(s"Snapshot reduce failed $snapshot")
    logger.info(s"Snapshot reduced without failures $snapshot")
    GCLog("after loadRecent")
    startUpSpaceProfiler.out(world.assembled)
    consuming.process(world.offset, consumer => {
      val initialRawObserver = progressObserverFactory.create(consumer.endOffset)
      iteration(consumer, world, initialRawObserver)
    })
  }
  @tailrec private def iteration(
    consumer: Consumer, world: RichContext, observer: Observer[RichContext]
  ): Unit = {
    val events = consumer.poll()
    if(events.nonEmpty){
      val latency = System.currentTimeMillis-events.map(_.mTime).min //check rec.timestampType == TimestampType.CREATE_TIME ?
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

