
package ee.cone.c4actor_branch

import ee.cone.c4actor.ArgTypes.LazyList
import ee.cone.c4actor._

import scala.collection.immutable.Seq
import ee.cone.c4actor_branch.BranchProtocol.S_BranchResult
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor_branch.BranchTypes.BranchKey
import ee.cone.c4proto._

object BranchTypes {
  type BranchKey = SrcId
}

trait BranchMessage extends Product {
  def method: String
  def header: String=>String
  def body: okio.ByteString
  def deletes: Seq[LEvent[Product]]
}

trait BranchHandler extends Product {
  def branchKey: SrcId
  def exchange: BranchMessage => Context => Context
  def seeds: Context => List[S_BranchResult]
}

trait BranchErrorSaver {
  def saveErrors(
    local: Context,
    branchKey: BranchKey,
    sessionKeys: List[SrcId],
    exceptions: List[Exception]
  ): Context
}

trait BranchTask extends Product {
  def branchKey: SrcId
  def product: Product
  def sessionKeys(visited: Set[SrcId] = Set.empty): Context => Set[BranchRel]
  type Send = Option[(String,String) => Context => Context]
  def sending: Context => (Send,Send)
  def relocate(to: String): Context => Context
}

trait BranchOperations {
  def toSeed(value: Product): S_BranchResult
  def toRel(seed: S_BranchResult, parentSrcId: SrcId, parentIsSession: Boolean): (SrcId,BranchRel)
}

case class BranchRel(srcId: SrcId, seed: S_BranchResult, parentSrcId: SrcId, parentIsSession: Boolean)



@protocol("BranchApp") object BranchProtocol   {
  @Id(0x0040) case class S_BranchResult(
    @Id(0x0041) hash: String,
    @Id(0x0042) valueTypeId: Long,
    @Id(0x0043) value: okio.ByteString,
    @Id(0x0044) children: LazyList[S_BranchResult],
    @Id(0x0045) position: String
  )

  @Id(0x004B) case class U_Redraw(
    @Id(0x004C) srcId: String,
    @Id(0x004D) branchKey: String
  )

  @Id(0x004E) case class N_RestPeriod(
    @Id(0x004D) branchKey: String,
    @Id(0x004F) value: Long
  )
}

trait ToAlienSender {
  def send(sessionKeys: Seq[String], evType: String, data: String): Context=>Context
}

trait BranchError {
  def message(local: Context): String
}