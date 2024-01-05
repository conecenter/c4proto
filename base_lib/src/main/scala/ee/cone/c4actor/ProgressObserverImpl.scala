package ee.cone.c4actor

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.{Future, Promise}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{c4assemble,Single}
import ee.cone.c4di.{c4, c4app, c4multi, provide}


import java.time.Instant

@c4("ServerCompApp") final
class ProgressObserverFactoryImpl(
  val inner: TxObserver, val execution: Execution,
  val config: Config, val sender: RawQSenderExecutable,
) extends ProgressObserverFactory {
  def create(endOffset: NextOffset): Observer[RichContext] =
    new ProgressObserverImpl(endOffset,0, this)
}

class ProgressObserverImpl(
  endOffset: NextOffset, until: Long=0, parent: ProgressObserverFactoryImpl,
) extends Observer[RichContext] with LazyLogging {
  import parent._
  def activate(rawWorld: RichContext): Observer[RichContext] =
    if (rawWorld.offset < endOffset) {
      val now = System.currentTimeMillis
      if(now < until) this else {
        logger.debug(s"loaded ${rawWorld.offset}/$endOffset")
        new ProgressObserverImpl(endOffset, now+1000, parent)
      }
    } else {
      logger.info(s"Stats OK -- loaded ALL/$endOffset -- uptime ${ManagementFactory.getRuntimeMXBean.getUptime}ms")
      val path = config.get("C4READINESS_PATH") match { case "" => throw new Exception case p => p }
      ignoreTheSamePath(Files.write(Paths.get(path),Array.empty[Byte]))
      execution.fatal(Future(sender.run())(_))
      inner.value.activate(rawWorld)
    }
  private def ignoreTheSamePath(path: Path): Unit = ()
}

////

@c4("ServerCompApp") final class ServerExecutionFilter(inner: ExecutionFilter)
  extends ExecutionFilter(e=>inner.check(e) && e.isInstanceOf[Early])

@c4assemble("ServerCompApp") class LateInitAssembleBase(
  factory: LateInitTxFactory
){
  def lateInit(
    key: SrcId,
    firstborn: Each[S_Firstborn],
  ): Values[(SrcId,TxTransform)] = List(WithPK(factory.create("LateInitTx")))
}

@c4multi("ServerCompApp") final case class LateInitTx(srcId: SrcId)(
  getToStart: DeferredSeq[Executable],
  execution: Execution,
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    val toStart = getToStart.value.filterNot(_.isInstanceOf[Early])
    logger.info(s"tracking ${toStart.size} late services")
    toStart.foreach(f => execution.unboundedFatal(Future(f.run())(_)))
    SleepUntilKey.set(Instant.MAX)(local)
  }
}