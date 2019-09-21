package ee.cone

import scala.annotation.StaticAnnotation

package object c4proto {
  class protocol(apps: String*) extends StaticAnnotation
  type Protocol = AbstractComponents
}
