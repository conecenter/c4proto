package ee.cone.c4actor

import ee.cone.c4assemble.JoinKey

class c4component
class listed
class c4key

trait ByUKGetterFactory {
  def forTypes[K,V<:Product](key: (String,String), value: (String,String)): ByUKGetter[K,V]
}

trait JoinKeyFactory {
  def forTypes[K,V<:Product](key: (String,String), value: (String,String)): JoinKey[K,V]
}
