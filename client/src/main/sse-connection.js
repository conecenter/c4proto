
let loadKeyState, connectionState;
let nextMessageIndex = 0
let eventSource
let lastOK = 0
let wasLocation

export default function SSEConnection({createEventSource,receiversList,reconnectTimeout,localStorage,sessionStorage,location,send}){
    function never(){ throw ["not ready"] }
    function pong(snd){
        snd(getConnectionState(never).pongURL, {
            "X-r-connection": getConnectionKey(never),
            "X-r-location": location+""
        })
        //console.log("pong")
    }
    function sessionKey(orDo){ return sessionStorage.getItem("sessionKey") || orDo() }
    function getLoadKey(orDo){ return loadKeyState || orDo() }
    function loadKeyForSession(){ return "loadKeyForSession-" + sessionKey(never) }
    function getConnectionState(orDo){ return connectionState || orDo() }
    function getConnectionKey(orDo){ return getConnectionState(orDo).key || orDo() }
    function connect(data,snd) {
        //console.log("conn: "+data)
        const [key,pongURL] = data.split(" ")
        connectionState = { key, pongURL }
        sessionKey(() => sessionStorage.setItem("sessionKey", getConnectionKey(never)))
        getLoadKey(() => { loadKeyState = getConnectionKey(never) })
        localStorage.setItem(loadKeyForSession(), getLoadKey(never))
        pong(snd)
    }
    function ping(data,snd) {
        //console.log("ping: "+data)
        if(localStorage.getItem(loadKeyForSession()) !== getLoadKey(never)) { // tab was refreshed/duplicated
            sessionStorage.clear()
            location.reload()
        } else if(getConnectionKey(never) === data) pong(snd) // was not reconnected
    }
    function sendBack(url,inHeaders){
        const headers = {
            ...inHeaders,
            "X-r-session": sessionKey(never),
            "X-r-index": nextMessageIndex++
        }
        //todo: contron message delivery at server
        send(url, {method:"post", headers})
        return headers
    }
    function relocateHash(data,snd) {
        location.href = "#"+data
    }
    function checkHash(snd){
        if(wasLocation!==location.href){
            wasLocation = location.href
            pong(snd)
        }
    }
    ////
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
            receiversList.concat({connect,ping,relocateHash}).forEach(
                handlerMap => Object.keys(handlerMap).forEach(
                    handlerName => eventSource.addEventListener(handlerName, 
                        event => handlerMap[handlerName](event.data,sendBack)
                    )
                )
            )
        }
        checkHash(sendBack)
    }
    return ({checkActivate})
}
