package ee.cone

import scala.annotation.StaticAnnotation

package object c4proto {
  class protocol(cat: OrigCategory*) extends StaticAnnotation
}

