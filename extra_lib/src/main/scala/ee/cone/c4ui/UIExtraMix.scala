package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4actor_branch.{BranchOperations, ToAlienSender}
import ee.cone.c4di.c4
import ee.cone.c4gate.ToAlienMessageUtil
import ee.cone.c4gate_akka.SimpleAkkaGatewayApp
import ee.cone.c4vdom.{ChildPairFactory, TagJsonUtils, VDomResolver}

import scala.collection.immutable.Seq

trait VDomApp extends ComponentProviderApp {
  lazy val childPairFactory: ChildPairFactory = resolveSingle(classOf[ChildPairFactory])
  lazy val tagJsonUtils: TagJsonUtils = resolveSingle(classOf[TagJsonUtils])
  lazy val vDomResolver: VDomResolver = resolveSingle(classOf[VDomResolver])
}

trait ExtraUIApp extends UICompApp with VDomApp with AlienExchangeApp with SimpleAkkaGatewayApp {
  lazy val branchOperations: BranchOperations = resolveSingle(classOf[BranchOperations])
  lazy val untilPolicy: UntilPolicy = resolveSingle(classOf[UntilPolicy])
}

////

@deprecated trait AlienExchangeAppBase extends AlienExchangeCompApp
@c4("AlienExchangeApp") final class SendToAlienInit(
  toAlienSender: ToAlienSender,
) extends ToInject {
  def toInject: List[Injectable] = SendToAlienKey.set(toAlienSender.send)
}
@deprecated @c4("AlienExchangeApp") final class ToAlienSenderImpl(
  toAlienMessageUtil: ToAlienMessageUtil, txAdd: LTxAdd
) extends ToAlienSender {
  def send(sessionKeys: Seq[String], evType: String, data: String): Context => Context = {
    val lEvents = for(k<-sessionKeys; ev<-toAlienMessageUtil.create(k, s"${evType}\n${data}")) yield ev
    txAdd.add(lEvents)
  }
}
