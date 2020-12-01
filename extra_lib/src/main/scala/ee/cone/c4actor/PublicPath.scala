package ee.cone.c4actor

import ee.cone.c4actor.PublicImage.RotationAngle

import scala.util.matching.{Regex, UnanchoredRegex}

trait ImageSize

trait PublicPathCollector {
  def allPaths: List[PublicPath]
}

trait PublicPath extends Product {
  def isEmpty: Boolean = path.trim.isEmpty
  def nonEmpty: Boolean = path.trim.nonEmpty
  def path: String
  def pathType: String

  def format(name: String, value: String): String = {
    assert(!value.contains("}"))
    ImagePublicPath.packFormat.format(name, value)
  }
  def convert: String = {
    s"${format(ImagePublicPath.pathType, pathType)} ${format(ImagePublicPath.path, path)}"
  }
}

object PublicImage{
  type RotationAngle = Long
}

trait ImagePublicPath extends PublicPath {
  def size: Option[ImageSize]
  def angle:Option[RotationAngle]
  def withSize(newSize: ImageSize): ImagePublicPath
  def withNoSize: ImagePublicPath
  def withRotation(a:RotationAngle): ImagePublicPath
  def withNoRotation:ImagePublicPath
}

case class DefaultPublicPath(path: String) extends PublicPath {
  def pathType: String = DefaultPublicPath.curPathType
}


case class NonSVGPublicPath(path: String, size: Option[ImageSize] = None, angle: Option[RotationAngle] = None) extends ImagePublicPath {
  def withSize(newSize: ImageSize): NonSVGPublicPath = copy(size = Some(newSize))
  def withNoSize: NonSVGPublicPath = copy(size = None)

  def withRotation(a: RotationAngle): NonSVGPublicPath = if(a == 0) withNoRotation else copy(angle = Option(a))
  def withNoRotation: NonSVGPublicPath = copy(size = None)

  def pathType: String = NonSVGPublicPath.curPathType
}

case class SVGPublicPath(path: String, size: Option[ImageSize] = None, angle: Option[RotationAngle] = None, color: String = "") extends ImagePublicPath {
  def withSize(newSize: ImageSize): SVGPublicPath = copy(size = Some(newSize))
  def withNoSize: SVGPublicPath = copy(size = None)

  def withRotation(a: RotationAngle): SVGPublicPath = if(a == 0) withNoRotation else copy(angle = Option(a))
  def withNoRotation: SVGPublicPath = copy(size = None)

  def withColor(newColor: String): SVGPublicPath = copy(color = newColor)
  def withAdaptiveColor: SVGPublicPath = copy(color = SVGPublicPath.adaptiveColor)

  def pathType: String = SVGPublicPath.curPathType
}

object ImagePublicPath {
  def packFormat: String = "%s={%s}"
  def unpackFormat: Regex = """(\w+)=\{(.*?)}""".r

  def path = "path"
  def pathType = "pathType"

  def unpack(url: String): PublicPath = {
    val map = ImagePublicPath.unpackFormat.findAllMatchIn(url)
      .map(regMatch => regMatch.group(1) -> regMatch.group(2)).toMap

    val pathOpt = map.get(path)
    val pathType_ = map.get(pathType)

    pathOpt.map{path_ =>
      pathType_.map{
        case SVGPublicPath.curPathType =>
          SVGPublicPath(path_)
        case NonSVGPublicPath.curPathType =>
          NonSVGPublicPath(path_)
        case DefaultPublicPath.curPathType =>
          DefaultPublicPath(path_)
      }.get
    }.getOrElse(DefaultPublicPath(url))
  }

  def matchImagePublicPath(publicPath: PublicPath): ImagePublicPath = publicPath match {
    case img: ImagePublicPath => img
    case _ => NonSVGPublicPath(publicPath.path)
  }

  implicit final class StringToPublicPath(private val str: String) extends AnyVal {
    def unpackImg: PublicPath = unpack(str)
  }

  implicit final class OptStringToPublicPath(private val strOpt: Option[String]) extends AnyVal {
    def unpackImgOpt: Option[PublicPath] = strOpt.map(unpack)
  }
}

object DefaultPublicPath {
  lazy val curPathType = "default"

  def empty = DefaultPublicPath("")
}

object NonSVGPublicPath {
  lazy val curPathType = "nonSVG"

  def empty = NonSVGPublicPath("")
}

object SVGPublicPath {
  lazy val curPathType = "svg"

  def adaptiveColor = "adaptive"

  def empty: SVGPublicPath = SVGPublicPath("")
}
