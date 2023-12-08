package ee.cone.c4assemble

import ee.cone.c4di.{c4, provide}

trait AssembleAppBase

@c4("AssembleApp") final class RIndexUtilProvider(arrayUtil: ArrayUtil)(inner: RIndexUtil = new RIndexUtilImpl(arrayUtil)()){
  @provide def getRIndexUtil: Seq[RIndexUtil] = Seq(inner)
    //Seq(new RIndexUtilDebug(inner))
}
