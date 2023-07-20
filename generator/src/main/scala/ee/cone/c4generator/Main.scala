package ee.cone.c4generator

import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration.Duration
import scala.meta.parsers.Parsed.{Error, Success}
import scala.meta._
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.matching.Regex

object Main {
  def defaultGenerators(protocolStatsTransformers: List[ProtocolStatsTransformer]): List[Generator] = {
    val protocolGenerator = new ProtocolGenerator(protocolStatsTransformers)
    val metaGenerator = new MetaGenerator(protocolStatsTransformers)
    List(
      ImportGenerator,
      new TimeGenerator(protocolGenerator, metaGenerator),
      new AssembleGenerator(TimeJoinParamTransformer :: Nil),
      metaGenerator,
      protocolGenerator,
      MultiGenerator,
      FieldAccessGenerator,
      AppGenerator,
      TagGenerator,
      ProtoChangerGenerator,
      //ProductCheckGenerator,
    ) //,UnBaseGenerator
  }
  def main(args: Array[String]): Unit = new RootGenerator(defaultGenerators(Nil) ::: List(
    ValInNonFinalGenerator
  )).run(args)
}

case class WillGeneratorContext(
  fromFiles: List[Path], dirInfo: Map[Path,ParentPath],
  workPath: Path, version: String,
  deps: Map[String, List[String]], modNames: List[String], modInfo: Map[String,ModInfo], tags: List[ConfItem]
)
trait WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path,Array[Byte])]
}

case class ParentPath(modDir: Path, removed: List[String], pkg: String, modInfo: ModInfo)
case class ConfItem(from: String, to: String)
class RootGenerator(generators: List[Generator], fromTextGenerators: List[FromTextGenerator]=Nil) {
  private def willGenerators: List[WillGenerator] = List(
    new DefaultWillGenerator(generators, fromTextGenerators),
    new PublicPathsGenerator,
    new ModRootsGenerator,
    new XsdWillGenerator,
  )
  //
  def isGenerated(fileName: String): Boolean =
    fileName.startsWith("c4gen.") || fileName.startsWith("c4gen-") || fileName.startsWith("c4msg.")

  def run(args: Array[String]): Unit = {
    val opt = args.grouped(2).map{ case Array(a,b) => (a,b) }.toMap
    val workPath = Paths.get(opt("--context"))
    val version = opt("--ver")
    //println(s"1:${System.currentTimeMillis()}") //130
    val conf = Files.readAllLines(workPath.resolve("target/c4/generator.conf")).asScala.toSeq.grouped(3).toSeq
      .groupMap(_.head)(_.tail match{ case Seq(fr,to) => ConfItem(fr,to) }).withDefaultValue(Nil)
    val dirs = for(it <- (conf("C4SRC") ++ conf("C4PUB")).distinct) yield workPath.resolve(it.to).toString
    val cmd = "find" :: dirs.toList ::: "-type" :: "f" :: Nil
    val filesA = new String(new ProcessBuilder(cmd:_*).start().getInputStream.readAllBytes(), UTF_8).split("\n") // Files.walk is much slower
    val (wasFiles,fromFilesAll) = filesA.partition{ path => isGenerated(path.substring(path.lastIndexOf("/")+1)) }
    val skipDirs = (for(it <- conf("C4GENERATOR_DIR_MODE") if it.from == "OFF") yield workPath.resolve(it.to)).toSet
    val fromFiles = fromFilesAll.map(Paths.get(_)).filterNot(path => skipDirs(path.getParent)).sorted.toList
    val was = (for { pathStr <- wasFiles } yield {
      val path = Paths.get(pathStr)
      path -> Files.readAllBytes(path)
    }).toMap
    //println(s"4:${System.currentTimeMillis()}") //1s
    val deps = conf("C4DEP").toList.groupMap(_.from)(_.to).withDefaultValue(Nil)
    val srcRoots = conf("C4SRC").toList.groupMap(_.from)(it=>workPath.resolve(it.to)).withDefaultValue(Nil)
    val pubRoots = conf("C4PUB").toList.groupMap(_.from)(it=>workPath.resolve(it.to)).withDefaultValue(Nil)
    val modNames = (conf("C4DEP").map(_.from) ++ conf("C4DEP").map(_.to)).distinct.sorted.toList
    val MainPkg = """(\w+)\.(.+)""".r
    val modInfo = modNames.map{ modName =>
      val MainPkg(gr, pkg) = modName
      val modGroupInfo = ModGroupInfo(gr, srcRoots(gr), pubRoots(gr))
      val srcDirs = modGroupInfo.srcDirs.map(_.resolve(pkg.replace('.', '/')))
      modName -> ModInfo(modName, modGroupInfo, pkg, srcDirs)
    }.toMap
    val modByModDir = modInfo.values.flatMap(modInfo => modInfo.srcDirs.map(p=>p->modInfo)).toMap
    val dirInfo: Map[Path, ParentPath] = fromFiles.map(_.getParent).distinct.flatMap(dir =>
      LazyList.iterate[Option[Path]](Option(dir))(po => po.flatMap(p => Option(p.getParent))).collectFirst {
        case None => None
        case Some(p) if modByModDir.contains(p) =>
          modByModDir.get(p).map { modInfo =>
            val removed = if(p == dir) Nil else p.relativize(dir).iterator.asScala.map(_.toString).toList
            //println(s"removed00 $dir $removed ${removed.size}")
            val pkg = (modInfo.pkg :: removed).mkString(".")
            dir -> ParentPath(p, removed, pkg, modInfo)
          }
      }.flatten
    ).toMap
    val tags = conf("C4TAG").toList
    val willGeneratorContext =
      WillGeneratorContext(fromFiles, dirInfo, workPath, version, deps, modNames, modInfo, tags)
    val will = willGenerators.flatMap(_.get(willGeneratorContext))
    assert(will.forall{ case(path,_) => isGenerated(path.getFileName.toString) })
    assert(will.size == will.map(_._1).toSet.size)
    //println(s"2:${System.currentTimeMillis()}")
    for(path <- was.keySet -- will.toMap.keys) {
      println(s"removing $path")
      Files.delete(path)
    }
    for{
      (path,data) <- will if !java.util.Arrays.equals(data,was.getOrElse(path,Array.empty))
    } {
      println(s"saving $path")
      Util.ignoreTheSamePath(Files.write(path,data))
    }
    //println(s"3:${System.currentTimeMillis()}")
  }
}

case class ModGroupInfo(name: String, srcDirs: List[Path], pubDirs: List[Path])
case class ModInfo(name: String, group: ModGroupInfo, pkg: String, srcDirs: List[Path])

object Cached {
  def apply(ctx: WillGeneratorContext, tasks: List[(Path,String,String=>String)]): List[(Path,Array[Byte])] = {
    val rootCachePath = Files.createDirectories(ctx.workPath.resolve("target/c4/gen/cache"))
    Await.result({
      implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
      Future.sequence(tasks.map(pt=>Future{
        val (path,postfix,transform) = pt
        val fromData = Files.readAllBytes(path)
        val uuidForPath = UUID.nameUUIDFromBytes(s"$path$postfix".getBytes(UTF_8)).toString
        val uuid = UUID.nameUUIDFromBytes(fromData).toString
        val cachePath = rootCachePath.resolve(s"$uuidForPath-$uuid-${ctx.version}")
        val out = if (Files.exists(cachePath)) Files.readAllBytes(cachePath) else {
          println(s"parsing $path")
          val toData = transform(new String(fromData,UTF_8)).getBytes(UTF_8)
          Util.ignoreTheSamePath(Files.write(cachePath, toData))
          toData
        }
        if(out.isEmpty) None else Option(path.getParent.resolve(s"c4gen.${path.getFileName}$postfix") -> out)
      }))
    },Duration.Inf).flatten
  }
}

case class FromTextGeneratorContext(pkg: String, fileName: String, content: String)
trait FromTextGenerator {
  def ext: String
  def get(context: FromTextGeneratorContext): List[Generated]
}

object JoinStr {
  def apply(parts: String*): String = parts.mkString
}

case class AutoMixerDescr(path: Path, name: String, content: String)
class DefaultWillGenerator(generators: List[Generator], fromTextGenerators: List[FromTextGenerator]) extends WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path,Array[Byte])] = {
    val ExtRegEx = """.*\.(\w+)""".r
    val pathsByExt = ctx.fromFiles.groupBy(_.getFileName.toString match {
      case ExtRegEx(ext) => ext
      case _ => ""
    }).withDefaultValue(Nil)
    val tasks = pathsByExt("scala").map(path=>(path, "", (content: String) => {
      getParseContext(path, content).fold("")(pCtx => addDI(pCtx, generators.flatMap(_.get(pCtx))))
    }))
    val fromTextTasks = for {
      generator <- fromTextGenerators
      path <- pathsByExt(generator.ext)
      dirInfo <- Util.dirInfo(ctx, path.getParent)
    } yield (path, ".scala", (textContent: String)=>{
      val generated = generator.get(FromTextGeneratorContext(dirInfo.pkg, path.getFileName.toString, textContent))
      addDI(new ParseContext(Nil, path.toString, dirInfo.pkg), generated)
    })
    withIndex(ctx)(Cached(ctx, tasks ++ fromTextTasks))
  }
  private def withIndex(ctx: WillGeneratorContext): List[(Path,Array[Byte])] => List[(Path,Array[Byte])] = args => {
    val pattern = """\((\S+)\s(\S+)\s(\S+)\)""".r
    val rawAppLinks = for {
      (path,data) <- args
      pos = data.indexOf('\n'.toByte)
      firstLine = new String(data,0,pos,UTF_8)
      dir = path.getParent
      mat <- pattern.findAllMatchIn(firstLine)
    } yield {
      val Seq(pkg,app,expr) = mat.subgroups
      dir -> GeneratedAppLink(pkg,app,expr)
    }
    val (rawDefAppLinks, customAppLinks) = rawAppLinks.partition{ case (_,link) => link.app == "DefApp" }
    val defAppLinks = for {
      (dir, link) <- rawDefAppLinks
      dirInfo <- Util.dirInfo(ctx, dir)
    } yield {
      val end = dirInfo.removed.map(n => s".$n").mkString
      assert(link.pkg.endsWith(end), s"${link.pkg} $end")
      val pkg = link.pkg.substring(0, link.pkg.length - end.length)
      val app = s"${Util.pkgNameToId(s".$pkg")}${link.app}"
      val expr = s"${link.pkg}.${link.expr}"
      dirInfo.modDir -> GeneratedAppLink(pkg, app, expr)
    }
    val indexes = (defAppLinks++customAppLinks).groupMap(_._1)(_._2).toList.sortBy(_._1).map{ case(parentPath,links) =>
        val Seq(pkg) = links.map(_.pkg).distinct
        val content =
          s"\n// THIS FILE IS GENERATED; C4APPS: ${links.filter(_.expr=="CLASS").map(l=>s"$pkg.${l.app}").mkString(" ")}" +
            s"\npackage $pkg" +
            links.groupBy(_.app).toList.collect{ case (app,links) =>
              val(classLinks,exprLinks) = links.partition(_.expr=="CLASS")
              val tp = if(classLinks.nonEmpty) "class" else "trait"
              val base = if(app.endsWith("DefApp")) "ee.cone.c4di.ComponentsApp"
              else s"${app}Base with ee.cone.c4di.ComponentsApp"
              s"\n$tp $app extends $base {" +
                s"\n  override def components: List[ee.cone.c4di.Component] = " +
                exprLinks.map(c=> s"\n    ${c.expr} ::: ").mkString +
                s"\n    super.components" +
                s"\n}"
            }.sorted.mkString
        parentPath.resolve("c4gen.scala") -> content.getBytes(UTF_8)
    }
    val defAppsByModDir = defAppLinks.groupMap(_._1){ case (_,v) => s"${v.pkg}.${v.app}" }.transform((_,v)=>v.distinct)
    val autoMixers = (new ByPriority[String,Option[AutoMixerDescr]](
      modName=>(ctx.deps(modName), depOpts => {
        val deps: List[AutoMixerDescr] = depOpts.flatten
        val modInfo = ctx.modInfo(modName)
        val pkg = modInfo.pkg
        val dirs = if(modInfo.group.name == "main") modInfo.srcDirs else Nil
        val defApps = for(dir <- dirs; defApp <- defAppsByModDir.getOrElse(dir,Nil)) yield defApp
        if (deps.isEmpty && defApps.isEmpty) None else {
          val Seq(dir) = dirs
          val defApp = defApps.mkString(" with ")
          val compContent = if(defApp.isEmpty) "Nil" else s"(new $defApp {}).components"
          val mixerName = s"${Util.pkgNameToId(s".$pkg")}AutoMixer"
          val content = JoinStr(
              s"package $pkg",
              s"\nobject $mixerName extends ee.cone.c4di.AutoMixer(",
              s"\n  () => $compContent,",
              deps.map(d => s"\n  ${d.name} ::").mkString,
              s"\n  Nil",
              s"\n)",
          )
          Option(AutoMixerDescr(dir.resolve("c4gen-base.scala"), s"$pkg.$mixerName", content))
        }
      })
    ))(ctx.modNames)
    val autoMixerIndexes = autoMixers.flatten.map(am=>am.path->am.content.getBytes(UTF_8))
    args ++ indexes ++ autoMixerIndexes
  }
  private def getParseContext(path: Path, content: String): Option[ParseContext] =
    dialects.Scala213(content.replace("\r\n", "\n")).parse[Source] match {
      case Parsed.Success(source"..$sourceStatements") =>
        if (sourceStatements.isEmpty) None else {
          val Seq(q"package $n { ..$packageStatements }") = sourceStatements
          val packageStatementsList = (packageStatements: Seq[Stat]).toList
          Option(new ParseContext(packageStatementsList, path.toString, n.syntax))
        }
      case Parsed.Error(position, string, ex) => throw new Exception(s"Parse exception in ($path:${position.startLine})", ex)
    }
  private def addDI(parseContext: ParseContext, generatedWOComponents: List[Generated]): String = {
    val parsedGenerated = generatedWOComponents.collect{ case c: GeneratedCode => c.content.parse[Stat] match { // c.content.parse[Source].get.stats}.flatten
      case Success(stat) => stat
      case Error(position, str, exception) =>
        println(c.content)
        throw exception
    }}
    val parsedAll = parseContext.stats ::: parsedGenerated
    val compParseContext = new ParseContext(parsedAll, parseContext.path, parseContext.pkg)
    val generated: List[Generated] = generatedWOComponents ::: ComponentsGenerator.get(compParseContext)
    if(generated.forall(_.isInstanceOf[GeneratedImport])) "" else {
      val head = generated.collect { case s: GeneratedAppLink => s"(${s.pkg} ${s.app} ${s.expr})" }.mkString
      val body = generated.flatMap {
        case c: GeneratedImport => List(c.content)
        case c: GeneratedCode => List(c.content)
        case _: GeneratedAppLink => Nil
        case c => throw new Exception(s"$c")
      }.mkString("\n")
      s"// THIS FILE IS GENERATED; APPLINKS: $head\n\npackage ${parseContext.pkg} {\n$body\n\n}"
    }
  }
}

object ImportGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap{
    case q"import ..$importers" =>
      val nextImporters = importers.map{
        case initialImporter@importer"$eref.{..$importeesnel}" =>
          importer"$eref._" match {
            case j@importer"ee.cone.c4di._" => j
            case j@importer"ee.cone.c4assemble._" => j
            case j@importer"ee.cone.c4proto._" => j
            case _ => initialImporter
          }
      }
      List(GeneratedImport("\n" + q"import ..$nextImporters".syntax))
    case _ => Nil
  }
}

object AppGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = for {
    cl <- Util.matchClass(parseContext.stats) if cl.mods.collectFirst{ case mod"@c4app" => true }.nonEmpty
    res <- cl.name match {
      case Util.UnBase(app) => List(GeneratedAppLink(parseContext.pkg,app,"CLASS"))
      case _ => Nil
    }
  } yield res
}

object Util {
  val UnBase: Regex = """(\w+)Base""".r
  /*
  def comment(stat: Stat): String=>String = cont =>
    cont.substring(0,stat.pos.start) + " /* " +
      cont.substring(stat.pos.start,stat.pos.end) + " */ " +
      cont.substring(stat.pos.end)*/

  def unBase(name: String, pos: Int)(f: String=>Seq[Generated]): Seq[Generated] =
    name match {
      case UnBase(n) => f(n)
      case n => throw new Exception(s"can not unBase $n") //List(Patch(pos,"Base"))
    }
  def matchClass(stats: List[Stat]): List[ParsedClass] = stats.flatMap{
    case q"..$cMods class ${nameNode@Type.Name(name)}[..$typeParams] ..$ctorMods (...$params) extends ..$ext { ..$stats }" =>
      List(new ParsedClass(cMods.toList,nameNode,name,typeParams.toList,params.map(_.toList).toList,ext.map{ case t:Tree=>t }.toList,stats.toList))
    case _ => Nil
  }


  def singleSeq[I](l: Seq[I]): Seq[I] = {
    assert(l.size<=1)
    l
  }

  def matchThisOrParent(cond: Path=>Boolean): Path=>Option[Path] = {
    def inner(path: Path): Option[Path] =
      if(cond(path)) Option(path) else Option(path.getParent).flatMap(inner)
    inner
  }

  def toBinFileList(in: Iterable[(Path,List[String])]): List[(Path,Array[Byte])] =
    in.map{ case (path,lines) => path->lines.mkString("\n").getBytes(UTF_8) }.toList.sortBy(_._1)

  def pathToId(fileName: String): String = {
    val SName = """.+/([-\w]+)\.([a-z]+)""".r
    val SName(fName, ext) = fileName
    fName.replace('-', '_') + (if(ext == "scala") "" else s"_$ext")
  }

  def dirInfo(ctx: WillGeneratorContext, dir: Path): List[ParentPath] = {
    val dirInfoOpt = ctx.dirInfo.get(dir)
    if (dirInfoOpt.isEmpty) println(s"missing module for $dir")
    dirInfoOpt.toList
  }

  def assertFinal(cl: ParsedClass): Unit = {
    assert(cl.mods.collect{ case mod"final" => true }.nonEmpty,s"${cl.name} should be final")
  }

  def ignoreTheSamePath(path: Path): Unit = ()

  def pkgNameToId(pkgName: String): String =
    """[\._]+([a-z])""".r.replaceAllIn(pkgName,m=>m.group(1).toUpperCase)
}

class ParsedClass(
  val mods: List[Mod], val nameNode: Type.Name, val name: String,
  val typeParams: List[Type.Param], val params: List[List[Term.Param]],
  val ext: List[Tree], val stats: List[Stat]
)
class ParseContext(val stats: List[Stat], val path: String, val pkg: String)
trait Generator {
  def get(parseContext: ParseContext): List[Generated]
}

sealed trait Generated
case class GeneratedImport(content: String) extends Generated
case class GeneratedCode(content: String) extends Generated
//case class Patch(pos: Int, content: String) extends Generated
case class GeneratedTraitDef(name: String) extends Generated
case class GeneratedTraitUsage(name: String) extends Generated
case class GeneratedAppLink(pkg: String, app: String, expr: String) extends Generated

case class PublicPathRoot(mainPublicPath: Path, pkgName: String, genPath: Path, publicPath: Path)
class PublicPathsGenerator extends WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path, Array[Byte])] = {
    val roots: List[PublicPathRoot] = for {
      path <- ctx.fromFiles.filter(_.getFileName.toString == "ht.scala")
      genPath = path.resolveSibling("c4gen.htdocs.scala")
      dirInfo <- Util.dirInfo(ctx, path.getParent)
      mainScalaPath <- Util.singleSeq(dirInfo.modInfo.group.srcDirs).toList
      relDir = mainScalaPath.relativize(path.getParent)
      mainPublicPath <- Util.singleSeq(dirInfo.modInfo.group.pubDirs).toList
      publicPath = mainPublicPath.resolve(relDir)
    } yield PublicPathRoot(mainPublicPath,dirInfo.pkg,genPath,publicPath)
    val isModRoot = roots.map(_.publicPath).toSet
    val toModRoot = Util.matchThisOrParent(isModRoot)
    val pathByRoot = ctx.fromFiles.groupBy(toModRoot)
    val RefOk = """([\w\-\./]+)""".r
    val ExtRegEx = """.*\.(\w+)""".r
    val fullMimeTypesMap: Map[String, String] = (for {
      path <- ctx.fromFiles.filter(_.getFileName.toString == "MimeTypesMap.scala")
      Parsed.Success(source"..$sourceStatements") = Files.readString(path).parse[Source]
      statement <- sourceStatements
      (ext,tp) <- statement.collect{
        case q"${Lit.String(ext)} -> ${Lit.String(tp)}" => (ext,tp)
      }
    } yield (ext,tp)).groupMapReduce(_._1)(_._2){(a,b)=>
      assert(a==b)
      a
    }
    val imageExtensions: Set[String] = fullMimeTypesMap.collect{
      case (key, value) if value.startsWith("image/") => key
    }.toSet
    Util.toBinFileList(roots.flatMap { (root:PublicPathRoot) =>

      val defs = pathByRoot.getOrElse(Option(root.publicPath),Nil)
        .map{ path =>
          val RefOk(r) = root.publicPath.relativize(path).toString
          val ExtRegEx(ext) = r
          val rel = root.mainPublicPath.relativize(path)
          val ref = s"/mod/main/$rel"

          val publicPath = ext match {
            case "svg" => s"""SVGPublicPath("$ref")"""
            case e if imageExtensions.contains(e) => s"""NonSVGPublicPath("$ref")"""
            case _ => s"""DefaultPublicPath("$ref")"""
          }
          (
            s"    def `/$r` = $publicPath ",
            s"main.${root.pkgName} $ref $rel",
            s"        `/$r` :: "
          )
        }
      val lines = /*if(defs.isEmpty) Nil else*/
        "/** THIS FILE IS GENERATED; CHANGES WILL BE LOST **/" ::
        s"package ${root.pkgName}" ::
        "import ee.cone.c4actor.{DefaultPublicPath, SVGPublicPath, NonSVGPublicPath, PublicPathCollector}" ::
        "abstract class PublicPathCollectorImpl extends PublicPathCollector {" ::
        "    def allPaths = " ::
        defs.map(_._3) :::
        "        Nil" ::
        defs.map(_._1) :::
        "}" :: Nil
      List(root.genPath -> lines, root.mainPublicPath.resolve("c4gen.ht.links") -> defs.map(_._2))
    }.groupMap(_._1)(_._2).transform((_,v)=>v.flatten))
  }
}

class ModRootsGenerator extends WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path, Array[Byte])] = {
    val unclearNames = ctx.modInfo.values.map(_.pkg).filter(_.contains("._")).toList.sorted.mkString(" ")
    if(unclearNames.nonEmpty) throw new Exception(s"module package name should avoid '._' ($unclearNames)")
    val badSubs = ctx.dirInfo.values
      .filter(d => d.modInfo.group.name=="main" && d.removed.nonEmpty && !d.removed.head.startsWith("_")).map(_.modDir)
      .toList.distinct.sorted.mkString(" ")
    if(badSubs.nonEmpty) throw new Exception(s"module dirs should have all children started with '_' ($badSubs)")
    Nil
  }
}


/*
git --git-dir=../.git --work-tree=target/c4generator/to  diff
perl run.pl
git --git-dir=../.git --work-tree=target/c4generator/to  diff target/c4generator/to/c4actor-base
*/
