package ee.cone.c4gate

import ee.cone.c4actor.Compressor

trait PublishFromStringsProvider {
  def get: List[(String,String)]
}

trait PublishMimeTypesProvider {
  def get: List[(String,String)]
}

class PublishFullCompressor(val value: Compressor)
