package ee.cone.c4actor_branch

import ee.cone.c4actor.Context

trait ToAlienSender {
  def send(sessionKeys: Seq[String], evType: String, data: String): Context=>Context
}

// it does not work; todo replace consistent blocking with optimistic updates
@deprecated trait BranchMessage extends Product
