package ee.cone.c4actor

abstract class AssembleName(name: String, classes: Class[_]*) {
  private lazy val classStr: String = if (classes.isEmpty) "" else s"[${classes.map(_.getSimpleName).mkString(",")}]"
  lazy val getName: String = s"$name$classStr"
}

