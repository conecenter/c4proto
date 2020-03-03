package ee.cone.c4generator

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._
import scala.meta.internal.trees.InternalTree
//import org.scalameta.logger

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
        case q"$o.of[$from, $to](...$args)" =>
          val fromTypeKey = ComponentsGenerator.getTypeKey(from, None).parse[Term].get
          val toTypeKey = ComponentsGenerator.getTypeKey(to, None).parse[Term].get
          val List(head :: tail) = args
          val q"_.$field" = head
          val nArgs = List(head :: q"value=>model=>model.copy($field=value)" ::
            Lit.String(s"$field") ::
            q"classOf[$from]" :: q"classOf[$to]" ::
            q"$fromTypeKey" :: q"$toTypeKey" ::
            tail)
          q"$o.ofSetStrict[$from, $to](...$nArgs)"
        case q"$o.ofFunc[$from, $to](...$args)" =>
          val List(field :: head :: tail) = args
          val fromTypeKey = ComponentsGenerator.getTypeKey(from, None).parse[Term].get
          val toTypeKey = ComponentsGenerator.getTypeKey(to, None).parse[Term].get
          val nArgs = List(head :: q"_ => _ => ???" ::
            q"$field" ::
            q"classOf[$from]" :: q"classOf[$to]" ::
            q"$fromTypeKey" :: q"$toTypeKey" ::
            tail)
          q"$o.ofSetStrict[$from, $to](...$nArgs)"
        case q"$o.ofFunc(...$args)" =>
          throw new Exception(s"$o.ofFunc($args) should have implicit types like $o.ofFunc[FROM, TO](...)")
        case q"$o.of(...$args)" =>
          val List(head :: tail) = args
          val q"_.$field" = head
          val nArgs = List(head :: q"value=>model=>model.copy($field=value)" :: Lit.String(s"$field") :: tail)
          q"$o.ofSet(...$nArgs)"
      }

      List(
        GeneratedImport("import ee.cone.c4di.TypeKey"),
        genObj(objectName, nCode)
      )
    }
  }
}

