package ee.cone.c4actor

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocolBase.S_Firstborn
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, c4assemble}
import ee.cone.c4di.c4

import scala.concurrent.Future

@c4("ServerCompApp") class ProgressObserverFactoryImpl(inner: TxObserver, config: ListConfig) extends ProgressObserverFactory {
  def create(endOffset: NextOffset): Observer[RichContext] = {
    val readyObserver = Single.option(config.get("C4ROLLING")).fold(inner.value)(path=>
      new ReadyObserverImpl(inner.value, Paths.get(path), 0L)
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
      logger.info(s"Stats OK -- loaded ALL/$endOffset")
      inner.activate(rawWorld)
    }
}

class ReadyObserverImpl(inner: Observer[RichContext], path: Path, until: Long=0) extends Observer[RichContext] with LazyLogging {
  def activate(rawWorld: RichContext): Observer[RichContext] = {
    if(until == 0) Files.write(path.resolve("c4is-ready"),Array.empty[Byte])
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
}

////

@c4("ServerCompApp") class ServerExecutionFilter(inner: ExecutionFilter)
  extends ExecutionFilter(e=>inner.check(e) && e.isInstanceOf[Early])

@c4assemble("ServerCompApp") class LateExecutionAssembleBase(execution: Execution, getToStart: DeferredSeq[Executable]){
  def join(
    srcId: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(LateExecutionTx("LateExecutionTx")(execution,getToStart.value.filterNot(_.isInstanceOf[Early]))))
}

case class LateExecutionTx(srcId: SrcId)(execution: Execution, toStart: Seq[Executable]) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    logger.info(s"tracking ${toStart.size} late services")
    toStart.foreach(f => execution.fatal(Future(f.run())(_)))
    SleepUntilKey.set(Instant.MAX)(local)
  }
}
