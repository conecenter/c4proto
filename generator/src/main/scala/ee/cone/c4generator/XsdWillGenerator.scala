package ee.cone.c4generator
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.UUID

class XsdWillGenerator  extends WillGenerator {
  override def get(ctx: WillGeneratorContext): List[(Path, Array[Byte])] = cached(ctx, "xsd", calc(ctx))(
    for(path <- ctx.fromFiles if getFileType(path.getFileName.toString).nonEmpty) yield path -> read(path)
  )
  def calc(ctx: WillGeneratorContext): TransformMany[String] = in => {
    val rMods = ctx.tags.map(tag => splitDropLast(".", tag.to)).distinct
    val fullDepsByMod = new ByPriority[String, (String, Set[String])](key =>
      (ctx.deps(key), deps => key -> deps.map(_._2).foldLeft(Set(key))((a, b) => a ++ b))
    )(rMods).toMap
    val rootModsByModDir = (for {
      rMod <- rMods
      mod <- fullDepsByMod(rMod)
      dir <- getSourceDirs(ctx, mod)
    } yield dir -> rMod).groupMap(_._1)(_._2).withDefaultValue(Nil)
    (for {
      (path, text) <- in
      modDir <- ctx.dirToModDir.get(path.getParent).toList
      rMod <- rootModsByModDir(modDir.path)
    } yield rMod -> (path, text)).groupMap(_._1)(_._2).toList.sortBy(_._1) .map{ case (rMod, parts) =>
      val Seq(toDir) = getSourceDirs(ctx, rMod)
      println(s"XSD DIR: ${toDir}"+parts.map{ case (path, text) => s"\n  $path" }.mkString)
      toDir.resolve("c4gen-raw-xsd") -> parts.map{ case (path, text) => text }.mkString("\n")
    }
  }
  private def getFileType(fn: String): String =
    if(fn == "messages.conf") "conf" else if(fn.endsWith(".xsd")) "xsd" else ""
  private def toText(data: Array[Byte]): String = new String(data, UTF_8)
  private def toBytes(text: String): Array[Byte] = text.getBytes(UTF_8)
  private def read(path: Path): Array[Byte] = Files.readAllBytes(path)
  private def write(path: Path, data: Array[Byte]): Unit = Files.write(path, data)
  private def splitDropLast(sp: String, v: String): String = v.substring(0, v.lastIndexOf(sp))
  private def getSourceDirs(ctx: WillGeneratorContext, modName: String): Seq[Path] = {
    val modHead :: modTail = modName.split("\\.").toList
    ctx.srcRoots(modHead).map(_.resolve(modTail.mkString("/")))
  }
  private type TransformMany[T] = List[(Path, T)] => List[(Path, T)]
  private def mkHash(data: Array[Byte]): String = UUID.nameUUIDFromBytes(data).toString
  private def toBytes(paths: List[Path]): Array[Byte] = toBytes(paths.map(_.toString).mkString("\n"))
  private def toPaths(data: Array[Byte]): List[Path] = toText(data).split("\n").map(Paths.get(_)).toList
  private def transpose[A,B](list: List[(A,B)]): (List[A], List[B]) = (list.map(_._1), list.map(_._2))
  def cached(ctx: WillGeneratorContext, tp: String, calc: TransformMany[String]): TransformMany[Array[Byte]] = in => {
    val (inPaths, inDatas) = transpose(in)
    val hash = mkHash(toBytes(ctx.version + mkHash(toBytes(inPaths)) + inDatas.map(mkHash)))
    val rootCachePath = Files.createDirectories(ctx.workPath.resolve(s"target/c4/gen/cache-$tp"))
    val cachePath = rootCachePath.resolve(hash)
    val partPaths = LazyList.from(0).map(pos=>rootCachePath.resolve(s"$hash.$pos"))
    if(Files.exists(cachePath)) toPaths(read(cachePath)).zip(partPaths.map(read)) else {
      println(s"parsing $tp:"+inPaths.map(p=>s"\n  $tp $p").mkString)
      val (outPaths, outTexts) = transpose(calc(inPaths.zip(inDatas.map(toText))))
      val outDatas = outTexts.map(toBytes)
      partPaths.zip(outDatas).toList.foreach((write _).tupled)
      write(cachePath, toBytes(outPaths))
      outPaths.zip(outDatas)
    }
  }
}

/*
val XmlWOHead = """\s*<\?[Xx][Mm][Ll][^>]*\?>\s*(.*)""".r
*/