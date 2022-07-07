package ee.cone.c4actor

abstract class GeneralEnableSimpleScaling(val cl: Class[_])

class EnableSimpleScaling[T](cl: Class[T]) extends GeneralEnableSimpleScaling(cl)
