package ee.cone.c4actor

trait PreHashing {
  def wrap[T](value: T): PreHashed[T]
}

trait PreHashed[T] {
  def value: T
}
