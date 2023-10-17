package ee.cone.c4assemble

import ee.cone.c4di.{c4, provide}

trait AssembleAppBase

@c4("AssembleApp") final class RIndexUtilProvider(
  memoryOptimizing: MemoryOptimizing
)(
  inner: RIndexUtil = new RIndexUtilImpl()()
){
  @provide def getRIndexUtil: Seq[RIndexUtil] = Seq(inner)
    //Seq(new RIndexUtilDebug(inner))
}

@c4("AssembleApp") final class MemoryOptimizingProvider {
  @provide def getMemoryOptimizing: Seq[MemoryOptimizing] = Seq(new MemoryOptimizingImpl)
}

final class MemoryOptimizingImpl(val indexPower: Int = 10/*12*/) extends MemoryOptimizing
