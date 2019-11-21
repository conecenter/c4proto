package ee.cone.c4actor.hashsearch.base

trait DepIndexNodeRegistry {
  def get[By <: Product, Field](byName: String, fieldName: String): String
}
