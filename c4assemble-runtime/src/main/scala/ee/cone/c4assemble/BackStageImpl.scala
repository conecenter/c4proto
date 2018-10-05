package ee.cone.c4assemble

import ee.cone.c4assemble.Types._

import scala.collection.immutable.Map

object PrepareBackStage extends WorldPartExpression {
  def transform(transition: WorldTransition): WorldTransition =
    transition.copy(prev=Option(transition), diff=emptyReadModel)
}

class ConnectBackStage[MapKey, Value](
  val outputWorldKey: AssembledKey,
  val nextKey:        AssembledKey,
  updater: IndexUpdater,
  composes: IndexUtil
) extends WorldPartExpression {
  def transform(transition: WorldTransition): WorldTransition = {
    val diffPart = nextKey.of(transition.prev.get.diff)
    //println(s"AAA: $nextKey $diffPart")
    //println(s"BBB: $transition")
    if(composes.isEmpty(diffPart)) transition
    else updater.setPart(outputWorldKey)(diffPart, nextKey.of(transition.result))(transition)
  }
}

class BackStageFactoryImpl(updater: IndexUpdater, composes: IndexUtil) extends BackStageFactory {
  def create(l: List[DataDependencyFrom[_]]): List[WorldPartExpression] = {
    val wasKeys = (for {
      e ← l
      key ← Single.option(e.inputWorldKeys.collect{
        case k:JoinKey if k.was ⇒ k
      }) // multiple @was are not supported due to possible different join loop rates
    } yield key).distinct
    PrepareBackStage :: wasKeys.map(k⇒new ConnectBackStage(k,k.withWas(was=false), updater, composes))
  }
}
