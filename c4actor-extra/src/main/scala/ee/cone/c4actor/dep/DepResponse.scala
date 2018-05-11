package ee.cone.c4actor.dep

import ee.cone.c4actor.{LazyHashCodeProduct, PreHashed}

case class DepOuterResponse(request: DepOuterRequest, valueHashed: PreHashed[Option[_]]) extends LazyHashCodeProduct {
  lazy val value: Option[_] = valueHashed.value
}

case class DepInnerResponse(request: DepInnerRequest, valueHashed: PreHashed[Option[_]]) extends LazyHashCodeProduct {
  lazy val value: Option[_] = valueHashed.value
}