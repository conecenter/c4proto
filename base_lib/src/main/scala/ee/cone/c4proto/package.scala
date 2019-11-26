package ee.cone

import ee.cone.c4di.AbstractComponents

import scala.annotation.StaticAnnotation

package object c4proto {
  class protocol(apps: String*) extends StaticAnnotation

  @deprecated type Protocol = AbstractComponents
  @deprecated type Component = ee.cone.c4di.Component
  @deprecated type c4 = ee.cone.c4di.c4
  @deprecated type provide = ee.cone.c4di.provide
  //@deprecated type TypeKey = ee.cone.c4di.TypeKey
  //@deprecated def TypeKey = ee.cone.c4di.TypeKey
  @deprecated type AutoMixer = ee.cone.c4di.AutoMixer
}
