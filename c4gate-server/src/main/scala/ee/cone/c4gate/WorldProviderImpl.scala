package ee.cone.c4gate

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4proto.{c4, provide}

import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class WorldProviderImpl(
  qMessages: QMessages,
  receiverF: Future[StatefulReceiver[WorldMessage]],
  offsetOpt: Option[NextOffset]
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
      val nLocal = TxAdd(events)(local)
      val offset = ReadAfterWriteOffsetKey.of(qMessages.send(nLocal))
      new TxRes(res,new WorldProviderImpl(qMessages,receiverF,Option(offset)))
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
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(WorldProviderTx()(worldProviderProvider.receiverFuture)))
}

case class WorldProviderTx(srcId: SrcId="WorldProviderTx")(receiverF: Future[StatefulReceiver[WorldMessage]]) extends TxTransform {
  def transform(local: Context): Context = concurrent.blocking{
    val nLocal = new Context(local.injected, local.assembled, local.executionContext, Map.empty)
    Await.result(receiverF, Duration.Inf).send(new WorldProviderMessage(nLocal))
    local
  }
}


@c4("WorldProviderApp") class WorldProviderProviderImpl(
  qMessages: QMessages,
  execution: Execution,
  statefulReceiverFactory: StatefulReceiverFactory,
  getOffset: GetOffset
)(
  receiverPromise: Promise[StatefulReceiver[WorldMessage]] = Promise()
) extends Executable {
  def receiverFuture: Future[StatefulReceiver[WorldMessage]] = receiverPromise.future
  @provide def getWorldProvider: Seq[WorldProvider] =
    List(new WorldProviderImpl(qMessages,receiverFuture,None))
  def run(): Unit = execution.fatal { implicit ec =>
    val receiverF = statefulReceiverFactory.create(List(new WorldProviderReceiverImpl(None,Nil,getOffset)))
    receiverPromise.completeWith(receiverF)
    receiverF
  }
}

class WorldProviderReceiverImpl(localOpt: Option[Context], waitList: List[WorldConsumerMessage], getOffset: GetOffset) extends Observer[WorldMessage] {
  def activate(message: WorldMessage): Observer[WorldMessage] = message match {
    case incoming: WorldProviderMessage =>
      val incomingOffset = getOffset.of(incoming.local)
      val(toHold,toResolve) = waitList.partition(sm=>sm.offsetOpt.exists(incomingOffset < _))

      toResolve.foreach(_.promise.success(incoming.local))
      new WorldProviderReceiverImpl(Option(incoming.local), toHold, getOffset)
    case incoming: WorldConsumerMessage if incoming.offsetOpt.isEmpty && localOpt.nonEmpty =>
      incoming.promise.success(localOpt.get)
      this
    case incoming: WorldConsumerMessage =>
      new WorldProviderReceiverImpl(localOpt, incoming :: waitList, getOffset)
  }
}

sealed trait WorldMessage
class WorldConsumerMessage(val promise: Promise[Context], val offsetOpt: Option[NextOffset]) extends WorldMessage
class WorldProviderMessage(val local: Context) extends WorldMessage
