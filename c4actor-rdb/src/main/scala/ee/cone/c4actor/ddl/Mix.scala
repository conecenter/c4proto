package ee.cone.c4actor.ddl

import ee.cone.c4actor.ExternalDBOption

trait DDLGeneratorApp {
  def ddlGeneratorHooks: DDLGeneratorHooks
  def externalDBOptions: List[ExternalDBOption]

  lazy val ddlGenerator = new DDLGeneratorImpl(externalDBOptions,ddlGeneratorHooks)
  lazy val ddlUtil = DDLUtilImpl
  lazy val ddlGeneratorOptionFactory = new DDLGeneratorOptionFactoryImpl(ddlUtil)
}
