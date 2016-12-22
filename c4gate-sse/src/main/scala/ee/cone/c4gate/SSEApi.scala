
package ee.cone.c4gate

import ee.cone.c4gate.InternetProtocol.TcpWrite

trait SSEMessages {
  def message(connectionKey: String, event: String, data: String, priority: Long): TcpWrite
}