package ee.cone.c4actor

import java.nio.charset.StandardCharsets.UTF_8
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.InjectionProtocol.S_InjectionDone
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{byEq, c4assemble}
import ee.cone.c4di.{c4, c4multi, provide}
import ee.cone.c4proto.{Id, protocol}

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.annotation.tailrec

@protocol("InjectionApp") object InjectionProtocol {
  @Id(0x00B5) case class S_InjectionDone(@Id(0x0011) srcId: SrcId, @Id(0x00B1) electorClientId: SrcId)
}

@c4assemble("InjectionApp") class InjectionAssembleBase(actorName: ActorName){
  def disable(
    key: SrcId,
    txTr: Each[TxTransform],
    beforeInjections: Values[BeforeInjection],
    @byEq[SrcId](actorName.value) states: Values[S_InjectionDone],
    @byEq[SrcId](actorName.value) processes: Each[ReadyProcesses],
  ): Values[(SrcId, DisableTxTr)] = {
    val isDone = states.exists(s => processes.enabledForCurrentRole.contains(s.electorClientId))
    if(!isDone && beforeInjections.isEmpty) Seq(WithPK(DisableTxTr(key))) else Nil
  }
}

@c4("InjectionApp") final class InjectionMain(
  actorName: ActorName, indentedParser: AbstractIndentedParser, updateFromUtil: UpdateFromUtil,
  currentProcess: CurrentProcess, readyProcessUtil: ReadyProcessUtil, worldProvider: WorldProvider,
  s3: S3Manager, currentTxLogName: CurrentTxLogName, execution: Execution, getBeforeInjection: GetByPK[BeforeInjection],
) extends Executable with Early with LazyLogging {
  import WorldProvider._
  private def until(cond: AssembledContext=>Boolean): Unit =
    worldProvider.run(List(w=>if(cond(w)) Stop() else Redo()):Steps[Unit])
  private def noActivity(world: AssembledContext): Boolean = getBeforeInjection.ofA(world).isEmpty
  private def isMaster(world: AssembledContext) =
    readyProcessUtil.getAll(world).enabledForCurrentRole.headOption.contains(currentProcess.id)
  private def injectionPart(part: String): Unit = {
    logger.info(s"will do $part")
    val path = s3.join(currentTxLogName, s"snapshots/.injection.$part")
    for(data <- execution.aWait(s3.get(path)(_))){
      val updates = indentedParser.toUpdates(new String(data, UTF_8))
      worldProvider.runUpdCheck(updateFromUtil.get(_, updates).map(RawTxEvent))
      execution.aWait(s3.delete(path)(_))
    }
  }
  def run(): Unit = {
    until(isMaster)
    logger.info("working as master")
    until(noActivity)
    injectionPart("del")
    until(noActivity)
    injectionPart("add")
    until(noActivity)
    logger.info(s"done")
    worldProvider.runUpdCheck(_=>LEvent.update(S_InjectionDone(actorName.value, currentProcess.id)))
  }
}

@c4("InjectionApp") final class TxTrLoggerProvider(config: ListConfig, factory: TxTrLoggerFactory){
  @provide def get: Seq[Executable] = if(config.get("C4DEBUG_TXTR").nonEmpty) Seq(factory.create()) else Nil
}

@c4multi("InjectionApp") final class TxTrLogger()(
  worldSource: WorldSource, getTxTr: GetByPK[TxTransform]
) extends Executable with Early with LazyLogging {
  private type Q = BlockingQueue[Either[RichContext,Unit]]
  def run(): Unit = {
    val queue: Q = new LinkedBlockingQueue
    worldSource.doWith(queue, ()=>iteration(queue, Map.empty))
  }
  private def fmt(v: Option[TxTransform]) = v.fold("-")(_.productPrefix)
  @tailrec private def iteration(queue: Q, was: Map[String,TxTransform]): Unit = iteration(queue, queue.take() match {
    case Left(world) =>
      val will = getTxTr.ofA(world)
      for(k <- (was.keySet ++ will.keySet).toSeq.sorted) if(was.get(k) != will.get(k))
        logger.info(s"tx changed [$k] ${fmt(was.get(k))} -> ${fmt(will.get(k))}")
      will
  })
}