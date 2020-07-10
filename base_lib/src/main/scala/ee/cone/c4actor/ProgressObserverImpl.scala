package ee.cone.c4actor

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, c4assemble}
import ee.cone.c4di.c4

import scala.annotation.tailrec
import scala.concurrent.Future

@c4("ServerCompApp") final class ProgressObserverFactoryImpl(
  inner: TxObserver, config: ListConfig,
  execution: Execution, getToStart: DeferredSeq[Executable]
) extends ProgressObserverFactory {
  def create(endOffset: NextOffset): Observer[RichContext] = {
    val lateExObserver: Observer[RichContext]  = new LateExecutionObserver(execution,getToStart.value,inner.value)
    val readyObserver = Single.option(config.get("C4ROLLING")).fold(lateExObserver)(path=>
      new ReadyObserverImpl(lateExObserver, Paths.get(path), 0L)
    )
    new ProgressObserverImpl(readyObserver,endOffset)
  }
}

// states:
//   loading
//   loading ready
//   master
// trans:
//   loading -> loading
//   loading -> loading ready
//   loading ready -> loading ready
//   loading ready -> master

class ProgressObserverImpl(inner: Observer[RichContext], endOffset: NextOffset, until: Long=0) extends Observer[RichContext] with LazyLogging {
  def activate(rawWorld: RichContext): Observer[RichContext] =
    if (rawWorld.offset < endOffset) {
      val now = System.currentTimeMillis
      if(now < until) this else {
        logger.debug(s"loaded ${rawWorld.offset}/$endOffset")
        new ProgressObserverImpl(inner, endOffset, now+1000)
      }
    } else {
      logger.info(s"Stats OK -- loaded ALL/$endOffset -- uptime ${ManagementFactory.getRuntimeMXBean.getUptime}ms")
      inner.activate(rawWorld)
    }
}

class ReadyObserverImpl(inner: Observer[RichContext], path: Path, until: Long=0) extends Observer[RichContext] with LazyLogging {
  private def ignoreTheSamePath(path: Path): Unit = ()
  def activate(rawWorld: RichContext): Observer[RichContext] = {
    if(until == 0) ignoreTheSamePath(Files.write(path.resolve("c4is-ready"),Array.empty[Byte]))
    val now = System.currentTimeMillis
    if(now < until) this
    else if(Files.exists(path.resolve("c4is-master"))) {
      logger.info(s"becoming master")
      inner.activate(rawWorld)
    } else {
      logger.debug(s"ready/waiting")
      new ReadyObserverImpl(inner, path, now+1000)
    }
  }

}
/*
@c4assemble("ServerCompApp") class BuildVerAssembleBase(config: ListConfig, execution: Execution){
  def join(
    srcId: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,TxTransform)] = for {
    path <- config.get("C4BUILD_VER_PATH")
    value <- config.get("C4BUILD_VER_VALUE")
  } yield WithPK(BuildVerTx("BuildVerTx",Paths.get(path),value)(execution))
}
case class BuildVerTx(srcId: SrcId, path: Path, value: String)(execution: Execution) extends TxTransform {
  def transform(local: Context): Context = {
    if(new String(Files.readAllBytes(path), UTF_8) != value) execution.complete()
    SleepUntilKey.set(Instant.ofEpochMilli(System.currentTimeMillis+1000))(local)
  }
}*/

@c4("ServerCompApp") final class LocalElectorDeath(config: ListConfig, execution: Execution) extends Executable with Early {
  def run(): Unit =
    for(path <- config.get("C4ELECTOR_PROC_PATH")) iteration(Paths.get(path))
  @tailrec private def iteration(path: Path): Unit = {
    if(Files.notExists(path)) execution.complete()
    Thread.sleep(1000)
    iteration(path)
  }
}

////

@c4("ServerCompApp") final class ExcludeExecutionFilter extends Exclude[ExecutionFilter]
@c4("ServerCompApp") final class ServerExecutionFilter(
  inner: ProbablyExcluded[ExecutionFilter]
) extends ExecutionFilter {
  def check(e: Executable): Boolean = inner.value.check(e) && e.isInstanceOf[Early]
}

class LateExecutionObserver(
  execution: Execution, toStart: Seq[Executable], inner: Observer[RichContext]
) extends Observer[RichContext] with LazyLogging {
  def activate(world: RichContext): Observer[RichContext] = {
    logger.info(s"tracking ${toStart.size} late services")
    toStart.filterNot(_.isInstanceOf[Early]).foreach(f => execution.fatal(Future(f.run())(_)))
    inner.activate(world)
  }
}
