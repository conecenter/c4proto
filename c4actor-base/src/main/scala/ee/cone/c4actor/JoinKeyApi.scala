package ee.cone.c4actor

import ee.cone.c4actor.Types._
import ee.cone.c4assemble.JoinKey

class c4component
class listed
class c4key

trait ByUKFactory {
  def forTypes[K,V<:Product](key: (String,String), value: (String,String)): ByUK[K,V]
  def forTypes[K,V<:Product](key: (String,String), longValue: String): ByUK[K,V]
}

trait ByPKFactory {
  def forTypes[V<:Product](value: (String,String)): ByPK[V]
  def forTypes[V<:Product](longValue: String): ByPK[V]
  def joinKey[V<:Product](longValue: String): ByFK[SrcId,V]
}

trait ByFKFactory {
  def forTypes[K,V<:Product](key: (String,String), value: (String,String)): ByFK[K,V]
  def forTypes[K,V<:Product](key: (String,String), longValue: String): ByFK[K,V]
}
