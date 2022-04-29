package ee.cone.c4assemble

import ee.cone.c4di.{c4, provide}

trait AssembleAppBase

@c4("AssembleApp") final class RIndexUtilFactoryProvider {
  @provide def get: Seq[RIndexUtilFactory] = Seq(new RIndexUtilFactoryImpl)
}