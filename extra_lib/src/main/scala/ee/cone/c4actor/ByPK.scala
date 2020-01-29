package ee.cone.c4actor

object ByPK {
  def apply[V<:Product](cl: Class[V]): ByPrimaryKeyGetter[V] =
    ByPrimaryKeyGetter(cl.getName)
}
//todo? def t[T[U],U](clO: Class[T[U]], cl1: Class[U]): Option[T[U]] = None

