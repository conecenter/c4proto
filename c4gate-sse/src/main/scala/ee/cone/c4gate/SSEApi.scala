
package ee.cone.c4gate

import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey
import ee.cone.c4gate.InternetProtocol.HttpPost

case object AllowOriginKey extends WorldKey[Option[String]](None)
case object PostURLKey extends WorldKey[Option[String]](None)


case object AlienExchangeKey extends WorldKey[(List[Map[String,String]],List[String]) ⇒ World ⇒ World]((_,_)⇒identity)
