package ee.cone.c4http

class ReceiverOfConnectionImpl(
  lifeCycle: LifeCycle,
  handlerLists: CoHandlerLists,
  registry: ConnectionRegistryImpl,
  framePeriod: Long,
  sender: SenderOfConnection
) extends ReceiverOfConnection with CoHandlerProvider {
  lazy val queue = new LinkedBlockingQueue[DictMessage]
  lazy val connectionKey = {
    val key = util.UUID.randomUUID.toString
    lifeCycle.onClose{()=>
      registry.store.remove(key)
      println(s"connection unregister: $key")
    }
    registry.store(key) = this
    println(s"connection   register: $key")
    key
  }
  def handlers = CoHandler(ActivateReceiver){ ()=>
    Option(queue.poll(framePeriod,TimeUnit.MILLISECONDS)).foreach(message=>
      handlerLists.list(FromAlienDictMessage).foreach(_(message))
    )
    handlerLists.list(ShowToAlien).flatMap(_()).foreach{
      case (command,content) => sender.sendToAlien(command,content)
    }
  } :: Nil
}



class ConnectionRegistryImpl extends ConnectionRegistry {
  lazy val store = TrieMap[String, ReceiverOfConnectionImpl]()
  def send(bnd: DictMessage) = store(bnd.value("X-r-connection")).queue.add(bnd)
}


