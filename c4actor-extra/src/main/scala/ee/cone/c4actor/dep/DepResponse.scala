package ee.cone.c4actor.dep

import ee.cone.c4actor.LazyHashCodeProduct

case class DepOuterResponse(request: DepOuterRequest, value: Option[_]) extends LazyHashCodeProduct

case class DepInnerResponse(request: DepInnerRequest, value: Option[_]) extends LazyHashCodeProduct