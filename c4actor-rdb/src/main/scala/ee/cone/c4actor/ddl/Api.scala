package ee.cone.c4actor.ddl

import ee.cone.c4actor.ExternalDBOption

trait Need
case class DropType(name: String, attributes: List[DropTypeAttr], uses: List[DropType])
case class DropTypeAttr(attrName: String, attrTypeName: String)
case class NeedCode(drop: String,  ddl: String) extends Need
case class GrantExecute(name: String) extends Need

trait DDLUtil {
  def createOrReplace(key: String, args: String, code: String): NeedCode
}

trait DDLGeneratorHooks {
  def function(
    fName: String, args: String, resType: String, body: String
  ): NeedCode
  def toDbName(tp: String, mod: String, size: Int): String
  def objectType: String
  def loop(enc: String): String
}

trait DDLGenerator {
  def generate(wasTypes: List[DropType], wasFunctionNameList: List[String]): List[String]
}

////

trait DDLGeneratorOptionFactory {
  def createOrReplace(key: String, args: String, code: String): ExternalDBOption
  def grantExecute(key: String): ExternalDBOption
  def dbUser(user: String): ExternalDBOption
}