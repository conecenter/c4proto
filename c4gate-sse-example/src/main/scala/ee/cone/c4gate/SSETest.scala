package ee.cone.c4gate

import java.time.Instant

import com.typesafe.scalalogging.LazyLogging

import Function.chain
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4ui._


class TestSSEApp extends ServerApp
  with `The EnvConfigImpl` with `The VMExecution`
  with KafkaProducerApp with KafkaConsumerApp
  with `The ParallelObserverProvider` with TreeIndexValueMergerFactoryApp
  with BranchApp
  with AlienExchangeApp
  with `The NoAssembleProfiler`
  with ManagementApp
  with FileRawSnapshotApp
  with `The TestSSEAssemble`
  with `The SSEAppAssemble`

//println(s"visit http://localhost:${config.get("C4HTTP_PORT")}/sse.html")


@c4component @listed case class SSEAppAssemble()(
  wrap: FromAlienTaskAssemble => Assembled
)(
  inner: Assembled = wrap(new FromAlienTaskAssemble("/sse.html"))
) extends Assembled {
  def dataDependencies: List[DataDependencyTo[_]] = inner.dataDependencies
}

@assemble class TestSSEAssemble {
  def joinView(
    key: SrcId,
    tasks: Values[BranchTask]
  ): Values[(SrcId,BranchHandler)] = {
    //println(s"joinView ${tasks}")
    for(task ← tasks) yield task.branchKey → TestSSEHandler(task.branchKey, task)
  }
}

case class TestSSEHandler(branchKey: SrcId, task: BranchTask) extends BranchHandler with LazyLogging {
  def exchange: BranchMessage ⇒ Context ⇒ Context = message ⇒ local ⇒ {
    val now = Instant.now
    val (keepTo,freshTo) = task.sending(local)
    val send = chain(List(keepTo,freshTo).flatten.map(_("show",s"${now.getEpochSecond}")))
    logger.info(s"TestSSEHandler $keepTo $freshTo")
    SleepUntilKey.set(now.plusSeconds(1)).andThen(send)(local)
  }
  def seeds: Context ⇒ List[BranchProtocol.BranchResult] = _ ⇒ Nil
}