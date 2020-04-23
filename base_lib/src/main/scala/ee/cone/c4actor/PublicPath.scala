package ee.cone.c4actor

trait ImageSize

trait PublicPath extends Product {
  def path: String
  //def withPath(newPath: String): PublicPath
}

trait ImagePublicPath extends PublicPath {
  def size: Option[ImageSize]
  def withSize(newSize: Option[ImageSize]): ImagePublicPath
}

object SVGPublicPath {
  def adaptiveColor = "adaptive"
}

case class DefaultPublicPath(path: String) extends PublicPath
  //def withPath(newPath: String): PublicPath = copy(path = newPath)


case class NonSVGPublicPath(path: String, size: Option[ImageSize] = None) extends ImagePublicPath {
  //def withPath(newPath: String): PublicPath = copy(path = newPath)
  def withSize(newSize: Option[ImageSize]): ImagePublicPath = copy(size = newSize)
}

case class SVGPublicPath(path: String, viewPort: String, size: Option[ImageSize] = None, color: String = "") extends ImagePublicPath {
  def withSize(newSize: Option[ImageSize]): ImagePublicPath = copy(size = newSize)
  def withColor(newColor: String): SVGPublicPath = copy(color = newColor)
  def withAdaptiveColor: SVGPublicPath = copy(color = SVGPublicPath.adaptiveColor)
  //def withPath(newPath: String): PublicPath = copy(path = newPath)
}
