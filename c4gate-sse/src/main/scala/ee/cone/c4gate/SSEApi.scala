
package ee.cone.c4gate

import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey
import ee.cone.c4gate.InternetProtocol.HttpPost

case object AllowOriginKey extends WorldKey[Option[String]](None)
case object PostURLKey extends WorldKey[Option[String]](None)

case object ToAlienKey extends WorldKey[World ⇒ (World, List[(String, String)])](_⇒throw new Exception)
case object FromAlienKey extends WorldKey[(String⇒Option[String]) ⇒ World ⇒ World](_⇒identity)
