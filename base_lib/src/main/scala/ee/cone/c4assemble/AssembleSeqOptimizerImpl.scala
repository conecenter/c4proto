package ee.cone.c4assemble

import ee.cone.c4assemble.Types._
import ee.cone.c4di.{c4, c4multi}

import scala.annotation.tailrec
import scala.collection.immutable.{Map, Seq}
import scala.concurrent.{ExecutionContext, Future}

@c4multi("AssembleApp") final class LoopExpression[MapKey, Value](
  outputWorldKeys: Seq[AssembledKey],
  loopOutputIndex: Int,
  wasOutputWorldKey: AssembledKey,
  main: WorldPartExpression, // with DataDependencyTo[Index[MapKey, Value]],
  continue: List[WorldPartExpression],
)(
  updater: IndexUpdater,
  composes: IndexUtil,
  //val outputWorldKey: AssembledKey[Index[MapKey, Value]] = main.outputWorldKey,
  continueF: WorldTransition=>WorldTransition = Function.chain(continue.map(h=>h.transform(_)))
) extends WorldPartExpression {
  private def inner(
    left: Int,
    transition: WorldTransition,
    wasSumDiffs: Option[Seq[Index]], //do not inclide transition.diff-s
  ): (IndexUpdates,IndexUpdates) = {
    val diffParts = outputWorldKeys.map(_.of(transition.diff))
    val sumDiffs = wasSumDiffs.fold(diffParts)(composes.zipMergeIndex(diffParts))
    if(composes.isEmpty(diffParts(loopOutputIndex))){
      val results = outputWorldKeys.map(_.of(transition.result))
      (
        new IndexUpdates(sumDiffs, results, Nil),
        new IndexUpdates(Seq(emptyIndex),Seq(results(loopOutputIndex)),Nil)
      )
    } else {
      assert(left > 0, s"unstable local assemble $diffParts")
      inner(left - 1, main.transform(continueF(transition)), Option(sumDiffs))
    }
  }
  def transform(transition: WorldTransition): WorldTransition = {
    val transitionA = main.transform(transition)
    if(transition eq transitionA) transition
    else finishTransform(transition, inner(1000, transitionA, None))
  }
  private def finishTransform(transition: WorldTransition, next: (IndexUpdates,IndexUpdates)): WorldTransition = {
    Function.chain(Seq(
      updater.setPart(outputWorldKeys,next._1,logTask = true),
      updater.setPart(Seq(wasOutputWorldKey),next._2,logTask = false)
    ))(transition)
  }
}

@c4("AssembleApp") final class ShortAssembleSeqOptimizer(
  backStageFactory: BackStageFactory,
  loopExpressionFactory: LoopExpressionFactory
) extends AssembleSeqOptimizer {
  private def getSingleKeys[K]: Seq[K] => Set[K] = _.groupBy(i=>i).collect{ case (k,Seq(_)) => k }.toSet
  def optimize: List[Expr]=>List[WorldPartExpression] = expressionsByPriority => {
    val singleOutputKeys: Set[AssembledKey] = getSingleKeys(expressionsByPriority.flatMap(_.outputWorldKeys))
    val singleInputKeys = getSingleKeys(expressionsByPriority.flatMap(_.inputWorldKeys))
    expressionsByPriority.map{ e =>
      Single.option(e.outputWorldKeys.map{ case k:JoinKey => k }.zipWithIndex.flatMap{ case (key,i) =>
        val wKey = key.withWas(was=true)
        if(
          singleOutputKeys(key) && singleInputKeys(wKey) &&
            e.inputWorldKeys.contains(wKey)
        ) loopExpressionFactory.create[Any,Any](
          e.outputWorldKeys, i, wKey, e, backStageFactory.create(List(e))
        ) :: Nil
        else Nil
      }).getOrElse(e)
    }
  }
}

class NoAssembleSeqOptimizer() extends AssembleSeqOptimizer {
  def optimize: List[Expr]=>List[WorldPartExpression] = l=>l
}
