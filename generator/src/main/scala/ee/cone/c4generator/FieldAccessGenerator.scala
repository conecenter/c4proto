package ee.cone.c4generator

import scala.meta._
import scala.meta.internal.trees.InternalTree

object FieldAccessGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap{
    case Defn.Object(Seq(mod"@fieldAccess"),baseObjectNameNode@Term.Name(baseObjectName),template) =>
      Util.unBase(baseObjectName, baseObjectNameNode.pos.end) { objectName =>
        val stats = genCode(parseContext, template.stats)
        val obj = Defn.Object(Nil, Term.Name(objectName), template.copy(stats=stats))
        List(
          GeneratedImport("import ee.cone.c4di.{TypeKey,CreateTypeKey}"),
          GeneratedImport("import ee.cone.c4actor.{CreateProdGetter,CreateProdLens}"),
          GeneratedCode("\n" + obj.syntax)
        )
      }
    case Defn.Trait(Seq(mod"@fieldAccess"),baseObjectNameNode@Type.Name(baseObjectName),x,y,code) =>
      Utils.parseError(code, parseContext, "not safe to use @fieldAccess on non-object-s")
    case Defn.Class(Seq(mod"@fieldAccess"),baseObjectNameNode@Type.Name(baseObjectName),x,y,code) =>
      Utils.parseError(code, parseContext, "not safe to use @fieldAccess on non-object-s")
    case _ => Nil
  }
  private def valToFieldName(nameStr: String): List[Term] = {
    val fieldShortName = Lit.String(nameStr)
    List(q"getClass.getName + '.' + $fieldShortName")
  }
  private def note(id: Int, hint: String): Unit = {
    //println(s"fieldAccess $id $hint")
  }
  private def genCode(parseContext: ParseContext, code: List[Stat]): List[Stat] = code.map {
    case q"..$mods val $name: $t[$from, $to] = ProdLens.of(...$args)" =>
      note(2,"ProdLens.of")
      val Pat.Var(Term.Name(nameStr)) = name
      val List((get@q"_.$fieldT") :: tail) = args
      val mArgs = List(get :: q"value=>model=>model.copy($fieldT=value)" :: tail)
      val nArgs = strict(from, to) :: valToFieldName(nameStr) :: mArgs
      q"..$mods val $name: $t[$from, $to] = CreateProdLens.ofSet[$from, $to](...$nArgs)"
    case q"..$mods val $name: $t[$from, $to] = ProdLens.ofSet(...$args)" =>
      note(1,"ProdLens.ofSet")
      val Pat.Var(Term.Name(nameStr)) = name
      val nArgs = strict(from, to) :: valToFieldName(nameStr) :: args.toList
      q"..$mods val $name: $t[$from, $to] = CreateProdLens.ofSet[$from, $to](...$nArgs)"
    case q"..$mods def $name(...$dargs): $t[$from, $to] = ProdLens.from(...$args)" =>
      note(7,"def ProdLens.from")
      val Term.Name(nameStr) = name
      val nArgs = strict(from, to) :: valToFieldName(nameStr) :: args.toList
      q"..$mods def $name(...$dargs): $t[$from, $to] = CreateProdLens.from[$from, $to](...$nArgs)"

    case q"..$mods val $name: $t[$from, $to] = ProdGetter.of(...$args)" =>
      note(0,"ProdGetter")
      val Pat.Var(Term.Name(nameStr)) = name
      val nArgs = strict(from, to) :: valToFieldName(nameStr) :: args.toList
      q"..$mods val $name: $t[$from, $to] = CreateProdGetter.of[$from, $to](...$nArgs)"
    case q"..$mods def $name(...$dargs): $t[$from, $to] = ProdGetter.from(...$args)" => //???
      note(6,"def ProdGetter")
      val Term.Name(nameStr) = name
      val nArgs = strict(from, to) :: valToFieldName(nameStr) :: args.toList
      q"..$mods def $name(...$dargs): $t[$from, $to] = CreateProdGetter.from[$from, $to](...$nArgs)"

    case q"..$mods def $name(...$dargs): $t[$from, $to] = UnsafeProdGetter.of(...$args)" =>
      note(4,"UnsafeProdGetter")
      val nArgs = strict(from, to) :: args.toList
      q"..$mods def $name(...$dargs): $t[$from, $to] = CreateProdGetter.of[$from, $to](...$nArgs)"

    case code: Stat => code.transform{
      case q"ProdLens.of[$from, $to](...$args)" =>
        note(3,"ProdLens.of[]")
        val List((get@q"_.$fieldT") :: tail) = args
        val mArgs = List(get :: q"value=>model=>model.copy($fieldT=value)" :: tail)
        val Term.Name(fieldStr) = fieldT
        val fieldName: List[Term] = List(Lit.String(fieldStr))
        val nArgs: List[List[Term]] = strict(from, to) :: fieldName :: mArgs
        q"CreateProdLens.ofSet[$from, $to](...$nArgs)"
      case t@Term.Name("ProdLens"|"ProdGetter"|"UnsafeProdGetter") =>
        Term.Name(s"${t.value}_Should_not_be_used_in_free_form")
    }
  }.map{ case s: Stat => s }

  private def strict(from: Type, to: Type): List[scala.meta.Term] = {
    val fromTypeKey: Term = ComponentsGenerator.getTypeKey(from, None).parse[Term].get
    val toTypeKey: Term = ComponentsGenerator.getTypeKey(to, None).parse[Term].get
    q"classOf[$from]" :: q"classOf[$to]" :: fromTypeKey :: toTypeKey :: Nil
  }
}

