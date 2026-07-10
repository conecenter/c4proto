package ee.cone.c4generator

import scala.meta._

private case class MsgVariant(
  parentName: String,
  className: String,
  action: String,
  bodyName: String,
  params: List[Term.Param],
  tree: Tree,
)

object MsgDecoderGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = {
    val switches = parseContext.stats.collect {
      case stat @ Defn.Trait(mods, Type.Name(name), _, _, _) if hasAnnotation(mods, "c4msgSwitch") =>
        name
    }.toSet
    if (switches.isEmpty) return Nil

    val variants = parseContext.stats.collect {
      case stat @ Defn.Class(mods, Type.Name(className), _, ctor, template) =>
        for {
          msg <- msgAnnotation(mods)
          parentName <- template.inits.collectFirst {
            case Init(Type.Name(name), _, _) if switches.contains(name) => name
          }
        } yield MsgVariant(parentName, className, msg.action, msg.body, ctor.paramss.flatten.toList, stat)
    }.flatten

    val decoders = variants.groupBy(_.parentName).toList.sortBy(_._1).flatMap { case (switchName, switchVariants) =>
      List(GeneratedCode(decoderObject(parseContext, switchName, switchVariants.sortBy(_.action))))
    }

    if (decoders.isEmpty) Nil
    else GeneratedImport("\nimport ee.cone.c4vdom.VDomMessage") ::
      GeneratedImport("\nimport okio.ByteString") ::
      decoders
  }

  private def hasAnnotation(mods: List[Mod], name: String): Boolean =
    mods.exists {
      case Mod.Annot(Init(Type.Name(annotationName), _, _)) if annotationName == name => true
      case _ => false
    }

  private case class MsgAnnotation(action: String, body: String)

  private def msgAnnotation(mods: List[Mod]): Option[MsgAnnotation] =
    mods.collectFirst {
      case Mod.Annot(Init(Type.Name("c4msg"), _, argss)) =>
        val args = argss.flatten
        val named = args.collect {
          case Term.Assign(Term.Name(name), Lit.String(value)) => name -> value
        }.toMap
        val positional = args.collect { case Lit.String(value) => value }
        MsgAnnotation(
          named.getOrElse("action", positional.headOption.getOrElse("")),
          named.getOrElse("body", positional.lift(1).getOrElse(""))
        )
    }

  private def decoderObject(parseContext: ParseContext, switchName: String, variants: List[MsgVariant]): String = {
    val cases = variants.map(v => s"case ${quote(v.action)} => Some(${decodeExpression(parseContext, v)})")
    val decoderName = s"${switchName}Decoder"
    JoinStr(
      s"\nobject $decoderName {",
      "\n  private def bodyString(message: VDomMessage): String = message.body match {",
      "\n    case value: ByteString => value.utf8()",
      "\n  }",
      "\n  def decode(message: VDomMessage): Option[", switchName, "] = header(message, \"X-r-action\") match {",
      cases.map(c => s"\n    $c").mkString,
      "\n    case _ => None",
      "\n  }",
      "\n  private def header(message: VDomMessage, name: String): String = {",
      "\n    val value = message.header(name)",
      "\n    if (value.nonEmpty) value else message.header(name.toLowerCase)",
      "\n  }",
      "\n}"
    )
  }

  private def decodeExpression(parseContext: ParseContext, variant: MsgVariant): String =
    variant.params match {
      case List(Term.Param(_, Term.Name(paramName), Some(Type.Name("String")), _)) if paramName == variant.bodyName =>
        s"${variant.className}(bodyString(message))"
      case List(Term.Param(_, Term.Name(paramName), Some(Type.Name("String")), _)) =>
        Utils.parseError(
          variant.tree,
          parseContext,
          s"@c4msg body ${variant.bodyName} does not match single String parameter $paramName"
        )
      case _ =>
        Utils.parseError(
          variant.tree,
          parseContext,
          "@c4msg decoder v1 supports only a single String body parameter"
        )
    }

  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
