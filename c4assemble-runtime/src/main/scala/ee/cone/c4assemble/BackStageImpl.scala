package ee.cone.c4assemble

import ee.cone.c4assemble.Types.Index

import scala.collection.immutable.Map

object PrepareBackStage extends WorldPartExpression {
  def transform(transition: WorldTransition): WorldTransition = {
    //println("pbs")
    WorldTransition(Option(transition), Map.empty, transition.result)
  }
}

class ConnectBackStage[MapKey, Value](
  val outputWorldKey: AssembledKey[Index[MapKey, Value]],
  val nextKey:        AssembledKey[Index[MapKey, Value]],
  updater: IndexUpdater
) extends WorldPartExpression {
  def transform(transition: WorldTransition): WorldTransition = {
    val diffPart = updater.diffOf(nextKey)(transition.prev.get)
    //println(s"AAA: $nextKey $diffPart")
    //println(s"BBB: $transition")
    if(diffPart.isEmpty) transition
    else updater.setPart(outputWorldKey)(diffPart, nextKey.of(transition.result))(transition)
  }
}

class BackStageFactoryImpl(updater: IndexUpdater) extends BackStageFactory {
  def create(l: List[DataDependencyFrom[_]]): List[WorldPartExpression] = {
    val wasKeys = (for {
      e ← l
      key ← Single.option(e.inputWorldKeys.collect{
        case k:JoinKey[_,_] if k.was ⇒ k.asInstanceOf[JoinKey[_,Product]]
      }) // multiple @was are not supported due to possible different join loop rates
    } yield key).distinct
    PrepareBackStage :: wasKeys.map(k⇒new ConnectBackStage(k,k.copy(was=false), updater))
  }
}
