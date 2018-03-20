package ee.cone.c4ui

import ee.cone.c4actor.{ToInject, ToInjectApp}

trait AccessViewApp extends AccessViewRegistryApp with InnerAccessViewRegistryApp

trait AccessViewRegistryApp {
  lazy val accessViewRegistry: AccessViewRegistry = AccessViewRegistryImpl
}

trait InnerAccessViewRegistryApp extends ToInjectApp {
  def accessViews: List[AccessView[_]]
  lazy val innerAccessViewRegistry = new InnerAccessViewRegistry(accessViews)
  override def toInject: List[ToInject] = innerAccessViewRegistry :: super.toInject
}