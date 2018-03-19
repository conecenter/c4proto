package ee.cone.c4assemble

import ee.cone.c4assemble.Types.Index

import scala.annotation.tailrec
import scala.collection.immutable.{Map, Seq}

class LoopExpression[MapKey, Value](
  outputWorldKey: AssembledKey[Index[MapKey, Value]],
  wasOutputWorldKey: AssembledKey[Index[MapKey, Value]],
  main: WorldPartExpression, // with DataDependencyTo[Index[MapKey, Value]],
  continue: List[WorldPartExpression],
  updater: IndexUpdater
)(
  //val outputWorldKey: AssembledKey[Index[MapKey, Value]] = main.outputWorldKey,
  continueF: WorldTransition⇒WorldTransition = Function.chain(continue.map(h⇒h.transform(_)))
) extends WorldPartExpression {
  @tailrec private def inner(
    left: Int, transition: WorldTransition, resDiff: Map[MapKey, Boolean]
  ): (Map[MapKey, Boolean], Index[MapKey, Value]) = {
    val transitionA = main.transform(transition)
    val diffPart = updater.diffOf(outputWorldKey)(transitionA)
    if(diffPart.isEmpty) (resDiff, outputWorldKey.of(transitionA.result))
    else if(left>0) inner(left - 1, continueF(transitionA), resDiff ++ diffPart)
    else throw new Exception(s"unstable local assemble ${transitionA.diff}")
  }
  def transform(transition: WorldTransition): WorldTransition = {
    //println("B")
    val(nextDiff,nextIndex) = inner(1000, transition, Map.empty)
    //println("E")
    Function.chain(Seq(
      updater.setPart(outputWorldKey)(nextDiff,nextIndex),
      updater.setPart(wasOutputWorldKey)(Map.empty,nextIndex)
    ))(transition)
  }
}

class ShortAssembleSeqOptimizer(
  backStageFactory: BackStageFactory,
  updater: IndexUpdater
) extends AssembleSeqOptimizer {
  private def getSingleKeys[K]: Seq[K] ⇒ Set[K] = _.groupBy(i⇒i).collect{ case (k,Seq(_)) ⇒ k }.toSet
  def optimize: List[Expr]⇒List[WorldPartExpression] = expressionsByPriority ⇒ {
    val singleOutputKeys: Set[AssembledKey[_]] = getSingleKeys(expressionsByPriority.map(_.outputWorldKey))
    val singleInputKeys = getSingleKeys(expressionsByPriority.flatMap(_.inputWorldKeys))
    expressionsByPriority.map{ e ⇒ e.outputWorldKey match {
      case key:JoinKey[_,_] ⇒
        val wKey = key.copy(was=true)
        if(
          singleOutputKeys(key) && singleInputKeys(wKey) &&
            e.inputWorldKeys.contains(wKey)
        ) new LoopExpression[Any,Any](
          key.asInstanceOf[AssembledKey[Index[Any,Any]]],
          wKey.asInstanceOf[AssembledKey[Index[Any,Any]]],
          e, backStageFactory.create(List(e)), updater
        )()
        else e
    }}
  }
}

class NoAssembleSeqOptimizer() extends AssembleSeqOptimizer {
  def optimize: List[Expr]⇒List[WorldPartExpression] = l⇒l
}
