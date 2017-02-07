
export default function SSEConnection(createEventSource,handlers,reconnectTimeout){
    let eventSource
    let lastOK = 0

    function isStateClosed(v){ return v === 2 }
    function checkActivate(){
        if(eventSource){
            if(!isStateClosed(eventSource.readyState)) lastOK = Date.now()
            if(Date.now() - lastOK > reconnectTimeout){
                eventSource.close();
                eventSource = null;
            }
        }
        if(!eventSource){
            //console.log("new EventSource")
            eventSource = createEventSource();
            handlers.forEach(
                handlerMap => Object.keys(handlerMap).forEach(
                    handlerName => eventSource.addEventListener(handlerName, 
                        event => handlerMap[handlerName](event.data)
                    )
                )
            )
        }
    }
    return ({checkActivate})
}
