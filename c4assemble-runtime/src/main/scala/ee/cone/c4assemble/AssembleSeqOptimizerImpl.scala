package ee.cone.c4assemble

import ee.cone.c4assemble.Types._
import ee.cone.c4proto.c4component

import scala.annotation.tailrec
import scala.collection.immutable.{Map, Seq}
import scala.concurrent.{ExecutionContext, Future}

class LoopExpression[MapKey, Value](
  outputWorldKey: AssembledKey,
  wasOutputWorldKey: AssembledKey,
  main: WorldPartExpression, // with DataDependencyTo[Index[MapKey, Value]],
  continue: List[WorldPartExpression],
  updater: IndexUpdater
)(composes: IndexUtil,
  //val outputWorldKey: AssembledKey[Index[MapKey, Value]] = main.outputWorldKey,
  continueF: WorldTransition=>WorldTransition = Function.chain(continue.map(h=>h.transform(_)))
) extends WorldPartExpression {
  private def inner(
    left: Int, transition: WorldTransition, resDiff: Index
  ): Future[IndexUpdate] = {
    implicit val executionContext: ExecutionContext = transition.executionContext.value
    for {
      diffPart <- outputWorldKey.of(transition.diff)
      res <- {
        if(composes.isEmpty(diffPart)) for {
          resVal <- outputWorldKey.of(transition.result)
        } yield new IndexUpdate(resDiff, resVal, Nil)
        else if(left > 0) inner(
          left - 1,
          main.transform(continueF(transition)),
          composes.mergeIndex(Seq(resDiff, diffPart))
        )
        else throw new Exception(s"unstable local assemble ${transition.diff}")
      }
    } yield res
  }
  def transform(transition: WorldTransition): WorldTransition = {
    val transitionA = main.transform(transition)
    if(transition eq transitionA) transition
    else finishTransform(transition, inner(1000, transitionA, emptyIndex))
  }
  def finishTransform(transition: WorldTransition, next: Future[IndexUpdate]): WorldTransition = {
    implicit val executionContext: ExecutionContext = transition.executionContext.value
    Function.chain(Seq(
      updater.setPart(outputWorldKey,next,logTask = true),
      updater.setPart(wasOutputWorldKey,next.map(update=>new IndexUpdate(emptyIndex,update.result,Nil)),logTask = false)
    ))(transition)
  }
}

@c4component("AssembleAutoApp") class ShortAssembleSeqOptimizer(
  composes: IndexUtil,
  backStageFactory: BackStageFactory,
  updater: IndexUpdater
) extends AssembleSeqOptimizer {
  private def getSingleKeys[K]: Seq[K] => Set[K] = _.groupBy(i=>i).collect{ case (k,Seq(_)) => k }.toSet
  def optimize: List[Expr]=>List[WorldPartExpression] = expressionsByPriority => {
    val singleOutputKeys: Set[AssembledKey] = getSingleKeys(expressionsByPriority.map(_.outputWorldKey))
    val singleInputKeys = getSingleKeys(expressionsByPriority.flatMap(_.inputWorldKeys))
    expressionsByPriority.map{ e => e.outputWorldKey match {
      case key:JoinKey =>
        val wKey = key.withWas(was=true)
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
  def optimize: List[Expr]=>List[WorldPartExpression] = l=>l
}
