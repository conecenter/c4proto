package ee.cone.c4assemble

import ee.cone.c4di.{c4, provide}

trait AssembleAppBase

@c4("AssembleApp") final class RIndexUtilProvider {
  @provide def get: Seq[RIndexUtil] = Seq(
    //new RIndexUtilImpl()(),
    new RIndexUtilDebug()
  )
}