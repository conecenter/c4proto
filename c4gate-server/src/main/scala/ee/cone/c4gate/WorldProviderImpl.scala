package ee.cone.c4gate

import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4actor._
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
// todo: fix? if TxAdd had done nothing offset will be minimal,
//  but promise does not resolve instantly;
// ex. pong can take 200ms until the next WorldProviderMessage

class WorldObserver(receiverF: Future[StatefulReceiver[WorldMessage]]) extends Observer[RichContext] {
  def activate(world: RichContext): Seq[Observer[RichContext]] = {
    Await.result(receiverF, Duration.Inf).send(new WorldProviderMessage(world))
    List(this)
  }
}

@c4("WorldProviderApp") class WorldProviderProviderImpl(qMessages: QMessages,
  execution: Execution, statefulReceiverFactory: StatefulReceiverFactory
)(
  receiverPromise: Promise[StatefulReceiver[WorldMessage]] = Promise()
) extends Executable {
  @provide def getWorldProvider: Seq[WorldProvider] =
    List(new WorldProviderImpl(qMessages,receiverPromise.future,None))
  @provide def getInitialObserverProvider: Seq[InitialObserverProvider] =
    List(new InitialObserverProvider(Option(new WorldObserver(receiverPromise.future))))
  def run(): Unit = execution.fatal { implicit ec =>
    val receiverF = statefulReceiverFactory.create(List(new WorldProviderReceiverImpl(None,Nil)))
    receiverPromise.completeWith(receiverF)
    receiverF
  }
}

class WorldProviderReceiverImpl(localOpt: Option[Context], waitList: List[WorldConsumerMessage]) extends Observer[WorldMessage] {
  def activate(message: WorldMessage): immutable.Seq[Observer[WorldMessage]] = message match {
    case incoming: WorldProviderMessage =>
      val global = incoming.global
      val(toHold,toResolve) = waitList.partition(sm=>sm.offsetOpt.exists(global.offset < _))
      val local = new Context(global.injected, global.assembled, global.executionContext, Map.empty)
      toResolve.foreach(_.promise.success(local))
      List(new WorldProviderReceiverImpl(Option(local), toHold))
    case incoming: WorldConsumerMessage if incoming.offsetOpt.isEmpty && localOpt.nonEmpty =>
      incoming.promise.success(localOpt.get)
      List(this)
    case incoming: WorldConsumerMessage =>
      List(new WorldProviderReceiverImpl(localOpt, incoming :: waitList))
  }
}

sealed trait WorldMessage
class WorldConsumerMessage(val promise: Promise[Context], val offsetOpt: Option[NextOffset]) extends WorldMessage
class WorldProviderMessage(val global: RichContext) extends WorldMessage
