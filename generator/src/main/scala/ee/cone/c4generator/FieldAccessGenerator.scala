package ee.cone.c4generator

import scala.meta._
import scala.meta.internal.trees.InternalTree

object FieldAccessGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap{
    case Defn.Object(Seq(mod"@fieldAccess"),baseObjectNameNode@Term.Name(baseObjectName),code) =>
      genCode(baseObjectNameNode, baseObjectName, code, (objectName, nCode) =>
        GeneratedCode("\n" + Defn.Object(Nil, Term.Name(objectName), nCode.asInstanceOf[Template]).syntax)
      )
    case Defn.Trait(Seq(mod"@fieldAccess"),baseObjectNameNode@Type.Name(baseObjectName),x,y,code) =>
      genCode(baseObjectNameNode, baseObjectName, code, (objectName, nCode) =>
        GeneratedCode("\n" + Defn.Trait(Nil, Type.Name(objectName), x, y, nCode.asInstanceOf[Template]).syntax)
      )
    case Defn.Class(Seq(mod"@fieldAccess"),baseObjectNameNode@Type.Name(baseObjectName),x,y,code) =>
      genCode(baseObjectNameNode, baseObjectName, code, (objectName, nCode) =>
        GeneratedCode("\n" + Defn.Class(Nil, Type.Name(objectName), x, y, nCode.asInstanceOf[Template]).syntax)
      )
    case _ => Nil
  }

  private def genCode(baseObjectNameNode: InternalTree, baseObjectName: String, code: Template, genObj: (String, Tree) => GeneratedCode): Seq[Generated] = {
    Util.unBase(baseObjectName, baseObjectNameNode.pos.end) { objectName =>
      //case q"@fieldAccess object $name $code" =>
      //  println(s"=-=$code")
      //Util.comment(code)(cont) +
      val nCode = code.transform {
        case q"ProdLens.of[$from, $to](...$args)" =>
          genOfSetStrictShort(from, to, args)
        case q"..$mods val $name: $t[$from, $to] = ProdLens.of(...$args)" =>
          q"..$mods val $name: $t[$from, $to] = ${genOfSetStrictShort(from, to, args)}"
        case q"..$mods def $name(...$dargs): $t[$from, $to] = ProdLens.of(...$args)" =>
          q"..$mods def $name(...$dargs): $t[$from, $to] = ${genOfSetStrictShort(from, to, args)}"
        case q"ProdLens.of(...$args)" =>
          throw new Exception(s"ProdLens.of($args) should have implicit types like ProdLens.of[FROM, TO](...)")

        case q"ProdLens.ofFunc[$from, $to](...$args)" =>
          genOfFuncStrict(from, to, args)
        case q"..$mods val $name: $t[$from, $to] = ProdLens.ofFunc(...$args)" =>
          q"..$mods val $name: $t[$from, $to] = ${genOfFuncStrict(from, to, args)}"
        case q"..$mods def $name(...$dargs): $t[$from, $to] = ProdLens.ofFunc(...$args)" =>
          q"..$mods def $name(...$dargs): $t[$from, $to] = ${genOfFuncStrict(from, to, args)}"
        case q"ProdLens.ofFunc(...$args)" =>
          throw new Exception(s"ProdLens.ofFunc($args) should have implicit types like ProdLens.ofFunc[FROM, TO](...)")

        case q"ProdLens.ofSet[$from, $to](...$args)" =>
          genOfSetStrict(from, to, args)
        case q"..$mods val $name: $t[$from, $to] = ProdLens.ofSet(...$args)" =>
          q"..$mods val $name: $t[$from, $to] = ${genOfSetStrict(from, to, args)}"
        case q"..$mods def $name(...$dargs): $t[$from, $to] = ProdLens.ofSet(...$args)" =>
          q"..$mods def $name(...$dargs): $t[$from, $to] = ${genOfSetStrict(from, to, args)}"
        case q"ProdLens.ofSet(...$args)" =>
          throw new Exception(s"ProdLens.ofSet($args) should have implicit types like ProdLens.ofSet[FROM, TO](...)")
      }

      List(
        GeneratedImport("import ee.cone.c4di.{TypeKey, CreateTypeKey}"),
        GeneratedImport("import ee.cone.base.util.Never"),
        genObj(objectName, nCode)
      )
    }
  }

  private def genOfFuncStrict(
    from: Type, to: Type,
    args: List[List[scala.meta.Term]]
  ): Term = {
    val List(field :: get :: tail) = args
    val fromTypeKey = ComponentsGenerator.getTypeKey(from, None).parse[Term].get
    val toTypeKey = ComponentsGenerator.getTypeKey(to, None).parse[Term].get
    val nArgs = List(get :: field ::
      q"classOf[$from]" :: q"classOf[$to]" ::
      q"$fromTypeKey" :: q"$toTypeKey" ::
      tail
    )
    q"ProdLens.ofFuncStrict[$from, $to](...$nArgs)"
  }

  private def genOfSetStrictShort(
    from: Type, to: Type,
    args: List[List[scala.meta.Term]]
  ): Term = {
    val fromTypeKey = ComponentsGenerator.getTypeKey(from, None).parse[Term].get
    val toTypeKey = ComponentsGenerator.getTypeKey(to, None).parse[Term].get
    val List(head :: tail) = args
    val q"_.$field" = head
    val nArgs = List(head :: q"value=>model=>model.copy($field=value)" ::
      Lit.String(s"$field") ::
      q"classOf[$from]" :: q"classOf[$to]" ::
      q"$fromTypeKey" :: q"$toTypeKey" ::
      tail
    )
    q"ProdLens.ofSetStrict[$from, $to](...$nArgs)"
  }

  private def genOfSetStrict(
    from: Type, to: Type,
    args: List[List[scala.meta.Term]]
  ): Term = {
    val fromTypeKey = ComponentsGenerator.getTypeKey(from, None).parse[Term].get
    val toTypeKey = ComponentsGenerator.getTypeKey(to, None).parse[Term].get
    val List(field :: get :: set :: tail) = args
    val nArgs = List(get :: set :: field ::
      q"classOf[$from]" :: q"classOf[$to]" ::
      q"$fromTypeKey" :: q"$toTypeKey" ::
      tail
    )
    q"ProdLens.ofSetStrict[$from, $to](...$nArgs)"
  }
}

