package ee.cone.c4actor

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.Future
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Replace, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import java.time.Instant
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.annotation.tailrec

@c4("ServerCompApp") final class WorldSourceImpl extends WorldSource with Executable with Early {
  type Q = BlockingQueue[Either[RichContext,_]]
  private sealed trait Message
  private class RichMessage(val world: RichContext) extends Message
  private class Subscribe(val queue: Q) extends Message
  private class Unsubscribe(val queue: Q) extends Message
  private val worldQueue = new LinkedBlockingQueue[Message]
  def put(world: RichContext): Unit = worldQueue.put(new RichMessage(world))
  def doWith[M,R](queue: BlockingQueue[Either[RichContext,M]], f: ()=>R): R = try{
    worldQueue.put(new Subscribe(queue.asInstanceOf[Q]))
    f()
  } finally worldQueue.put(new Unsubscribe(queue.asInstanceOf[Q]))
  def run(): Unit = iteration(None, Nil)
  @tailrec private def iteration(worldOpt: Option[RichContext], subscribers: List[Q]): Unit = worldQueue.take() match {
    case m: RichMessage =>
      subscribers.foreach(_.put(Left(m.world)))
      iteration(Option(m.world), subscribers)
    case m: Subscribe =>
      worldOpt.foreach(w=>m.queue.put(Left(w)))
      iteration(worldOpt, m.queue :: subscribers)
    case m: Unsubscribe => iteration(worldOpt, subscribers.filterNot(_ eq m.queue))
  }
  def ready(world: RichContext, readAfterWriteOffsetOpt: Option[NextOffset]): Boolean =
    !readAfterWriteOffsetOpt.exists(world.offset < _)
}

class EnabledObserver(worldSource: WorldSourceImpl) extends Observer[RichContext]{
  def activate(world: RichContext): Observer[RichContext] = {
    worldSource.put(world)
    this
  }
}

object NoObserver extends Observer[RichContext] {
  def activate(world: RichContext): Observer[RichContext] = this
}

class ReportingObserver(replace: Replace) extends Observer[RichContext] {
  def activate(world: RichContext): Observer[RichContext] = {
    replace.report(world.assembled)
    NoObserver
  }
}

@c4("ServerCompApp") final
class ProgressObserverFactoryImpl(
  worldSource: WorldSourceImpl, disable: List[DisableDefObserver], replace: Replace,
  val execution: Execution,
  val config: Config, val sender: RawQSenderExecutable,
)(
  val inner: Observer[RichContext] =
    if(disable.nonEmpty) new ReportingObserver(replace) else new EnabledObserver(worldSource)
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
      inner.activate(rawWorld)
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