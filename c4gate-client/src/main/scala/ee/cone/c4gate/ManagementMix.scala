package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.{Assemble, IndexUtil, ReadModelUtil}

trait ManagementApp extends AssemblesApp with ActorAccessApp with PrometheusApp {
  def config: Config
  def indexUtil: IndexUtil
  def readModelUtil: ReadModelUtil
  def actorName: String
  def catchNonFatal: CatchNonFatal

  lazy val syncTxFactory: SyncTxFactory = new SyncTxFactoryImpl

  override def assembles: List[Assemble] =
    new ManagementPostAssemble(actorName, indexUtil, readModelUtil, catchNonFatal) ::
    new HttpConsumerAssemble(actorName, syncTxFactory) ::
    super.assembles
}

/*
*
* Usage:
* curl $gate_addr_port/manage/$app_name -XPOST -Hx-r-world-key:$worldKey -Hx-r-selection:(all|keys|:$key)
* curl 127.0.0.1:8067/manage/ee.cone.c4gate.TestPasswordApp -XPOST -Hx-r-world-key:SrcId,TxTransform -Hx-r-selection:all
*
* */