package ee.cone.c4generator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.xml.{Elem, TopScope, PrettyPrinter}

class XsdWillGenerator extends WillGenerator {
  override def get(ctx: WillGeneratorContext): List[(Path, Array[Byte])] = {
    val files = ctx.fromFiles.filter(path => getFileType(path.getFileName.toString).nonEmpty)
    MultiCached.cached(ctx, "xsd", calcAll(ctx), files)
  }

  private def groupDef[K,V](in: List[(K,V)]): Map[K, List[V]] = in.groupMap(_._1)(_._2).withDefaultValue(Nil)
  private def groupSort[K,V](in: List[(K,V)])(implicit o: Ordering[K]): List[(K, List[V])] =
    in.groupMap(_._1)(_._2).toList.sortBy(_._1)

  private def calcAll(ctx: WillGeneratorContext): MultiCached.TransformMany[String] = in => {
    val rootModsByModDir = groupDef(for {
      rMod <- ctx.tags.map(tag => splitDropLast(".", tag.to)).distinct
      mod <- getFull(ctx.deps.transform((_,v)=>v.toSet).withDefaultValue(Set.empty), Set(rMod)).toList.sorted
      dir <- ctx.modInfo(mod).srcDirs
    } yield dir -> rMod)
    groupSort(for {
      (path, text) <- in
      dirInfo <- Util.dirInfo(ctx, path.getParent)
      rMod <- rootModsByModDir(dirInfo.modDir)
    } yield rMod -> (path, text)).flatMap { case (rMod, parts) =>
      val Seq(toDir) = ctx.modInfo(rMod).srcDirs
      calcRMod(toDir)(parts)
    }
  }

  private def getFileType(fn: String): String =
    if (MessagesConfParser.supports(fn)) "conf" else if (fn.endsWith(".xsd")) "xsd" else ""

  private def splitDropLast(sp: String, v: String): String = v.substring(0, v.lastIndexOf(sp))

  private val xsn = "http://www.w3.org/2001/XMLSchema"

  private def provideElements(in: Seq[Elem]): Seq[Elem] = {
    val c4ns = "http://cone.dev"
    val useN = s"{$c4ns}use"
    val provideN = s"{$c4ns}provide"
    val provided = groupDef(for {
      e <- (in \\ "_").toList
      ks <- List(e \@ provideN) if ks.nonEmpty
      k <- ks.split("""\s+""")
    } yield k -> e)
    def tr(e: Elem): Elem = {
      val use = e \@ useN
      val child = if (use.isEmpty) e.child else provided(use)
      val attributes = e.attributes.remove(c4ns, e.scope, "use").remove(c4ns, e.scope, "provide")
      e.copy(scope = TopScope, child = child.map { case ce: Elem => tr(ce) case o => o }, attributes = attributes)
    }
    in.map(tr)
  }

  private def getFull(deps: Map[String, Set[String]], startNames: Set[String]): Set[String] =
    LazyList.iterate(startNames :: Nil)(r => (r.head ++ r.head.flatMap(deps)) :: r).tail.dropWhile{ r =>
      //println(s"AN:${r.size} ${r.head.size} ${r.tail.head.size}")
      r.head != r.tail.head
    }.head.head

  private def calcRMod(toDir: Path): MultiCached.TransformMany[String] = in => {
    println(s"XSD DIR: $toDir" + in.map { case (path, _) => s"\n  in $path" }.mkString)
    val textsByType = groupDef(in.map { case (path, text) => getFileType(path.getFileName.toString) -> (path, text) })
    val MessageNumber = """\s*MSG#(\d+).*""".r
    val elements = provideElements(textsByType("xsd").sortBy{
      case (path, _) => (path.getFileName, path)
    }.flatMap{ case (path, text) =>
      scala.xml.XML.loadString(text).child.flatMap {
        case e: xml.Elem if e.getNamespace(e.prefix) == xsn => Option(e)
        case t: xml.Text if t.text.forall(_.isWhitespace) => None
        case a => throw new Exception(s"bad 1st level node ($a) at $path")
      }.filter(_.label match {
        case "element" | "simpleType" | "complexType" => true
        case "include" => false
        case a => throw new Exception(s"bad 1st level element label ($a) at $path")
      })
    }).groupBy(e => (e.label, (e \ "annotation" \ "documentation").text) match {
      case ("element", MessageNumber(s)) =>
        println(s"text 1: ${s}")
        (0, s.toInt)
      case ("element", t) =>
        println(s"text 0: ${t}")
        (1, 0)
      case _ => (2, 0)
    }).toList.sortBy(_._1).flatMap(_._2)
    val deps = elements.map(el => (el \@ "name") -> ((el \\ "@base") ++ (el \\ "@type")).map(_.text).toSet)
      .groupMapReduce(_._1)(_._2)(_++_).withDefaultValue(Set.empty)
    val (dirList, systemList) = MessagesConfParser.parse(textsByType("conf").map(_._2))
    if(systemList.size != 1) Nil else groupSort(
      for((ms, f, t) <- dirList if systemList.contains(f) || systemList.contains(t); sys <- Seq(f,t)) yield sys -> ms
    ).map{ case (sys, startNameList) =>
      val startNames = startNameList.toSet
      val accessibleNames = getFull(deps, startNames)
      val enabledElements = elements.filter(e => accessibleNames(e \@ "name"))
      val fn = systemList match { case Seq(s) if s == sys => s"c4msg.$s.all.xsd" case Seq(s) => s"c4msg.$s-$sys.xsd" }
      println(s"  out $fn -- ${startNames.size} messages in conf -- ${enabledElements.size}/${elements.size} elements")
      val content = new PrettyPrinter(120, 4, true).format(<xs:schema xmlns:xs={xsn}/>.copy(child=enabledElements))
      toDir.resolve(fn) -> s"""<?xml version="1.0" encoding="UTF-8"?>\n$content"""
    }
  }
}

object MessagesConfParser {
  def supports(fn: String): Boolean = fn == "messages.conf"
  def parse(texts: List[String]): (List[(String, String, String)], List[String]) = {
    val linesByCmd = texts.flatMap(_.split("\n")).map(_.split("#").head)
      .map(_.split(":")).groupMap(_.head)(_.tail.toList)
    (linesByCmd.keySet -- Set("DIR", "SYSTEM", "")).toList.sorted.foreach(cmd => throw new Exception(s"bad cmd $cmd"))
    val DirRe = """\s*(\w+)\s+(\w+)\s*->\s*(\w+)\s*""".r
    val dirs= linesByCmd.getOrElse("DIR", Nil).map { case Seq(DirRe(msg, from, to)) => (msg, from, to) }
    val systems = linesByCmd.getOrElse("SYSTEM", Nil).map { case Seq(s) => s.trim }
    (dirs, systems)
  }
}

class MessagesConfTextGenerator(imp: String) extends FromTextGenerator {
  override def ext: String = "conf"

  override def get(context: FromTextGeneratorContext): List[Generated] =
    if(!MessagesConfParser.supports(context.fileName)) Nil else {
      val (dirList, sysList) = MessagesConfParser.parse(List(context.content))
      List(GeneratedImport(imp), GeneratedCode(JoinStr(
        "\n@c4 final class MessageConfProvider {",
        s"\n  @provide def systems: Seq[MessageSystem] = Seq(",
        sysList.map{ s => s"""\n    MessageSystem("$s"),"""}.mkString,
        "\n  )",
        s"\n  @provide def directions: Seq[MessageDirection] = Seq(",
        dirList.map{ case (m,f,t) => s"""\n    MessageDirection("$m","$f","$t"),"""}.mkString,
        "\n  )",
        "\n}"
      )))
    }
}

object MultiCached {
  type TransformMany[T] = List[(Path, T)] => List[(Path, T)]

  private def toText(data: Array[Byte]): String = new String(data, UTF_8)

  private def toBytes(text: String): Array[Byte] = text.getBytes(UTF_8)

  private def read(path: Path): Array[Byte] = Files.readAllBytes(path)

  private def write(path: Path, data: Array[Byte]): Unit = Files.write(path, data)

  private def mkHash(data: Array[Byte]): String = UUID.nameUUIDFromBytes(data).toString

  private def toBytes(paths: List[Path]): Array[Byte] = toBytes(paths.map(_.toString).mkString("\n"))

  private def toPaths(data: Array[Byte]): List[Path] = toText(data).split("\n").map(Paths.get(_)).toList

  private def transpose[A, B](list: List[(A, B)]): (List[A], List[B]) = (list.map(_._1), list.map(_._2))

  def cached(
    ctx: WillGeneratorContext, tp: String, calc: TransformMany[String], inPaths: List[Path]
  ): List[(Path, Array[Byte])] = {
    val inDatas = inPaths.map(read)
    val hash = mkHash(toBytes(ctx.version + mkHash(toBytes(inPaths)) + inDatas.map(mkHash)))
    val rootCachePath = Files.createDirectories(ctx.workPath.resolve(s"target/c4/gen/cache-$tp"))
    val cachePath = rootCachePath.resolve(hash)
    val partPaths = LazyList.from(0).map(pos => rootCachePath.resolve(s"$hash.$pos"))
    if (Files.exists(cachePath)) toPaths(read(cachePath)).zip(partPaths.map(read)) else {
      println(s"parsing $tp:" + inPaths.map(p => s"\n  $tp $p").mkString)
      val (outPaths, outTexts) = transpose(calc(inPaths.zip(inDatas.map(toText))))
      val outDatas = outTexts.map(toBytes)
      partPaths.zip(outDatas).toList.foreach((write _).tupled)
      write(cachePath, toBytes(outPaths))
      outPaths.zip(outDatas)
    }
  }
}
