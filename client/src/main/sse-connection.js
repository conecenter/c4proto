
export default function SSEConnection(createEventSource,handlers,reconnectTimeout){
    let eventSource = null
    let lastOK = 0
    function checkActivate(modify){
        if(eventSource && Date.now() - lastOK > reconnectTimeout){
            eventSource.close()
            eventSource = null
        }
        if(!eventSource){
            eventSource = createEventSource();
            lastOK = Date.now()
            eventSource.addEventListener("ping", ev => { lastOK = Date.now() })
            handlers.forEach(
                handlerMap => Object.keys(handlerMap).forEach(
                    handlerName => eventSource.addEventListener(handlerName,
                        event => handlerMap[handlerName](event.data,modify)
                    )
                )
            )
        }
    }
    return ({checkActivate})
}
