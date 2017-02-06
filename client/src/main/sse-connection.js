
export default function SSEConnection(createEventSource,handlers,reconnectTimeout){
    let eventSource
    let closedCount = 0

    function isStateClosed(v){ return v === 2 }
    function checkReconnect(){
        if(eventSource){
            closedCount = isStateClosed(eventSource.readyState) ? closedCount + 1 : 0
            //if(closedCount > 0) console.log("closedCount: "+closedCount)
            if(closedCount > reconnectTimeout){
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

    setInterval(checkReconnect,1000);
    checkReconnect();
}
