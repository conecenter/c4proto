package ee.cone.c4actor

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocolBase.S_Firstborn
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4di.c4

import scala.concurrent.Future

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
