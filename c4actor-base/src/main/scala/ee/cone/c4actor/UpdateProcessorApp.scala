package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.N_Update

import scala.collection.immutable
import scala.collection.immutable.Seq

class DefaultUpdateProcessor extends UpdateProcessor {
  def process(updates: immutable.Seq[QProtocol.N_Update]): immutable.Seq[QProtocol.N_Update] = updates
}

trait DefaultUpdateProcessorApp {
  def updateProcessor: UpdateProcessor = new DefaultUpdateProcessor
}
