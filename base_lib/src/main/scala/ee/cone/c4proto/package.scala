package ee.cone

import ee.cone.c4di.AbstractComponents

import scala.annotation.StaticAnnotation

package object c4proto {
  class protocol(apps: String*) extends StaticAnnotation

  @deprecated type Protocol = AbstractComponents
}
