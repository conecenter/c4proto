package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update

import scala.collection.immutable
import scala.collection.immutable.Seq

trait UpdateProcessor {
  def process(updates: Seq[Update]): Seq[Update]
}

class DefaultUpdateProcessor extends UpdateProcessor {
  def process(updates: immutable.Seq[QProtocol.Update]): immutable.Seq[QProtocol.Update] = updates
}

trait UpdateProcessorApp {
  def updateProcessor: UpdateProcessor = new DefaultUpdateProcessor
}
