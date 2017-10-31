package ee.cone.c4actor

import ee.cone.c4assemble.JoinKey

@c4component case class ByUKGetterFactoryImpl(
  joinKeyFactory: JoinKeyFactory
) extends ByUKGetterFactory {
  def forTypes[K,V<:Product](key: (String,String), value: (String,String)): ByUKGetter[K,V] =
    ByUKGetter[K,V](joinKeyFactory.forTypes(key, value))
}

@c4component case class JoinKeyFactoryImpl() extends JoinKeyFactory {
  def forTypes[K,V<:Product](key: (String,String), value: (String,String)): JoinKey[K,V] = {
    val (aliasedKey,longKey) = key
    val (_,longValue) = value
    JoinKey[K,V](aliasedKey,longKey,longValue)
  }
}
