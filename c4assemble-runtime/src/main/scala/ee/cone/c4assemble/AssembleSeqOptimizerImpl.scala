package ee.cone.c4assemble

import ee.cone.c4assemble.Types._

import scala.annotation.tailrec
import scala.collection.immutable.{Map, Seq}

class LoopExpression[MapKey, Value](
  outputWorldKey: AssembledKey,
  wasOutputWorldKey: AssembledKey,
  main: WorldPartExpression, // with DataDependencyTo[Index[MapKey, Value]],
  continue: List[WorldPartExpression],
  updater: IndexUpdater
)(composes: IndexFactory,
  //val outputWorldKey: AssembledKey[Index[MapKey, Value]] = main.outputWorldKey,
  continueF: WorldTransition⇒WorldTransition = Function.chain(continue.map(h⇒h.transform(_)))
) extends WorldPartExpression {
  @tailrec private def inner(
    left: Int, transition: WorldTransition, resDiff: Index
  ): (Index, Index) = {
    val transitionA = main.transform(transition)
    val diffPart = outputWorldKey.of(transitionA.diff)
    if(diffPart.isEmpty) (resDiff, outputWorldKey.of(transitionA.result))
    else if(left>0) inner(left - 1, continueF(transitionA), composes.mergeIndex(resDiff,diffPart))
    else throw new Exception(s"unstable local assemble ${transitionA.diff}")
  }
  def transform(transition: WorldTransition): WorldTransition = {
    //println("B")
    val(nextDiff,nextIndex) = inner(1000, transition, emptyIndex)
    //println("E")
    Function.chain(Seq(
      updater.setPart(outputWorldKey)(nextDiff,nextIndex),
      updater.setPart(wasOutputWorldKey)(emptyIndex,nextIndex)
    ))(transition)
  }
}

class ShortAssembleSeqOptimizer(
  composes: IndexFactory,
  backStageFactory: BackStageFactory,
  updater: IndexUpdater
) extends AssembleSeqOptimizer {
  private def getSingleKeys[K]: Seq[K] ⇒ Set[K] = _.groupBy(i⇒i).collect{ case (k,Seq(_)) ⇒ k }.toSet
  def optimize: List[Expr]⇒List[WorldPartExpression] = expressionsByPriority ⇒ {
    val singleOutputKeys: Set[AssembledKey] = getSingleKeys(expressionsByPriority.map(_.outputWorldKey))
    val singleInputKeys = getSingleKeys(expressionsByPriority.flatMap(_.inputWorldKeys))
    expressionsByPriority.map{ e ⇒ e.outputWorldKey match {
      case key:JoinKey ⇒
        val wKey = key.copy(was=true)
        if(
          singleOutputKeys(key) && singleInputKeys(wKey) &&
            e.inputWorldKeys.contains(wKey)
        ) new LoopExpression[Any,Any](
          key, wKey, e, backStageFactory.create(List(e)), updater
        )(composes)
        else e
    }}
  }
}

class NoAssembleSeqOptimizer() extends AssembleSeqOptimizer {
  def optimize: List[Expr]⇒List[WorldPartExpression] = l⇒l
}
