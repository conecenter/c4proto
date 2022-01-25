package ee.cone.c4gate_server

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4di.{c4, provide}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class WorldProviderImpl(
  qMessages: QMessages,
  receiverF: Future[StatefulReceiver[WorldMessage]],
  offsetOpt: Option[NextOffset],
  txAdd: LTxAdd,
) extends WorldProvider {
  def tx[R](f: Context=>(List[LEvent[Product]],R))(implicit executionContext: ExecutionContext): Future[TxRes[R]] = {
    val promise = Promise[Context]()
    for {
      receiver <- receiverF
      local <- {
        receiver.send(new WorldConsumerMessage(promise,offsetOpt))
        promise.future
      }
    } yield {
      val (events,res) = f(local)
      val nLocal = txAdd.add(events)(local)
      val offset = ReadAfterWriteOffsetKey.of(qMessages.send(nLocal))
      new TxRes(res,new WorldProviderImpl(qMessages,receiverF,Option(offset),txAdd))
    }
  }
}
// todo: fix? if TxAdd had done nothing, offset will be minimal,
//  but promise does not resolve instantly;
// ex. pong can take 200ms until the next WorldProviderMessage

@c4assemble("WorldProviderApp") class WorldProviderAssembleBase(worldProviderProvider: WorldProviderProviderImpl){
  def join(
    srcId: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,EnabledTxTr)] =
    List(WithPK(EnabledTxTr(WorldProviderTx()(worldProviderProvider.receiverFuture))))
}

case class WorldProviderTx(srcId: SrcId="WorldProviderTx")(receiverF: Future[StatefulReceiver[WorldMessage]]) extends TxTransform {
  def transform(local: Context): Context = concurrent.blocking{
    val nLocal = new Context(local.injected, local.assembled, local.executionContext, Map.empty)
    Await.result(receiverF, Duration.Inf).send(new WorldProviderMessage(nLocal))
    local
  }
}


@c4("WorldProviderApp") final class WorldProviderProviderImpl(
  qMessages: QMessages,
  execution: Execution,
  statefulReceiverFactory: StatefulReceiverFactory,
  getOffset: GetOffset,
  txAdd: LTxAdd,
)(
  receiverPromise: Promise[StatefulReceiver[WorldMessage]] = Promise()
) extends Executable with Early {
  def receiverFuture: Future[StatefulReceiver[WorldMessage]] = receiverPromise.future
  @provide def getWorldProvider: Seq[WorldProvider] =
    List(new WorldProviderImpl(qMessages,receiverFuture,None,txAdd))
  def run(): Unit = execution.fatal { implicit ec =>
    val receiverF = statefulReceiverFactory.create(List(new WorldProviderReceiverImpl(None,Nil)(getOffset,execution)))
    ignorePromise(receiverPromise.completeWith(receiverF))
    receiverF
  }
  private def ignorePromise[T](value: Promise[T]): Unit = ()
}

class WorldProviderReceiverImpl(localOpt: Option[Context], waitList: List[WorldConsumerMessage])(getOffset: GetOffset, execution: Execution) extends Observer[WorldMessage] {
  def activate(message: WorldMessage): Observer[WorldMessage] = message match {
    case incoming: WorldProviderMessage =>
      val incomingOffset = getOffset.of(incoming.local)
      val(toHold,toResolve) = waitList.partition(sm=>sm.offsetOpt.exists(incomingOffset < _))
      toResolve.foreach(m=>execution.success(m.promise,incoming.local))
      new WorldProviderReceiverImpl(Option(incoming.local), toHold)(getOffset,execution)
    case incoming: WorldConsumerMessage if incoming.offsetOpt.isEmpty && localOpt.nonEmpty =>
      execution.success(incoming.promise, localOpt.get)
      this
    case incoming: WorldConsumerMessage =>
      new WorldProviderReceiverImpl(localOpt, incoming :: waitList)(getOffset,execution)
  }
}

sealed trait WorldMessage
class WorldConsumerMessage(val promise: Promise[Context], val offsetOpt: Option[NextOffset]) extends WorldMessage
class WorldProviderMessage(val local: Context) extends WorldMessage
