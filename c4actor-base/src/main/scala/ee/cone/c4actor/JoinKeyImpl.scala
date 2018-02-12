package ee.cone.c4actor

import ee.cone.c4actor.Types._
import ee.cone.c4assemble.Types.Index
import ee.cone.c4assemble.{AssembledKey, JoinKey, Single}

import scala.collection.immutable.Map

case class ByUKImpl[K,V<:Product](joinKey: AssembledKey[Index[K,V]])
  extends ByUK[K,V]
{
  def of: Context ⇒ Map[K,V] = context ⇒
    UniqueIndexMap(joinKey.of(context.assembled))
}

case class UniqueIndexMap[K,V](index: Index[K,V]) extends Map[K,V] {
  def +[B1 >: V](kv: (K, B1)): Map[K, B1] = UniqueIndexMap(index + (kv._1→List(kv._2)))
  def get(key: K): Option[V] = Single.option(index.getOrElse(key,Nil))
  def iterator: Iterator[(K, V)] = index.iterator.map{ case (k,v) ⇒ (k,Single(v)) }
  def -(key: K): Map[K, V] = UniqueIndexMap(index - key)
  override def keysIterator: Iterator[K] = index.keysIterator // to work with non-Single
}

@c4component case class ByUKFactoryImpl(
  inner: ByFKFactory
) extends ByUKFactory {
  def forTypes[K,V<:Product](key: (String,String), value: (String,String)): ByUK[K,V] =
    ByUKImpl[K,V](inner.forTypes(key, value))
  def forTypes[K,V<:Product](key: (String,String), longValue: String): ByUK[K,V] =
    ByUKImpl[K,V](inner.forTypes(key, longValue))
}

@c4component case class ByPKFactoryImpl(
  byUK: ByUKFactory, byFK: ByFKFactory
)(key: (String,String) = ("SrcId",classOf[SrcId].getName)) extends ByPKFactory {
  def forTypes[V<:Product](value: (String,String)): ByPK[V] = byUK.forTypes(key,value)
  def forTypes[V<:Product](longValue: String): ByPK[V] = byUK.forTypes(key,longValue)
  def joinKey[V<:Product](longValue: String): ByFK[SrcId,V] = byFK.forTypes(key,longValue)
}

@c4component case class ByFKFactoryImpl() extends ByFKFactory {
  def forTypes[K,V<:Product](key: (String,String), value: (String,String)): ByFK[K,V] = {
    val (_,longValue) = value
    forTypes[K,V](key,longValue)
  }
  def forTypes[K,V<:Product](key: (String,String), longValue: String): ByFK[K,V] = {
    val (aliasedKey,longKey) = key
    JoinKey[K,V](was=false,aliasedKey,longKey,longValue)
  }
}
