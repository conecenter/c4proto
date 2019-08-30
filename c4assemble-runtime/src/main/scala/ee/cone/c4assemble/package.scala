package ee.cone

import scala.annotation.StaticAnnotation

package object c4assemble {
  class assemble extends StaticAnnotation
  class fieldAccess extends StaticAnnotation
  class ignore extends StaticAnnotation
  type MakeJoinKey = IndexFactory⇒JoinKey
}
