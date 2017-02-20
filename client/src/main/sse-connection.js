
// functional mostly

export default function SSEConnection({createEventSource,receiversList,reconnectTimeout,localStorage,sessionStorage,location,send}){
    const never = () => { throw ["not ready"] }
    const pong = state => state.addSend(state.pongURL||never(), {
        "X-r-connection": state.connectionKey || never(),
        "X-r-location": location+""
    })(state)
    const sessionKey = orDo => sessionStorage.getItem("sessionKey") || orDo()
    const loadKeyForSession = () => "loadKeyForSession-" + sessionKey(never)
    const connect = data => state => {
        //console.log("conn: "+data)
        const [connectionKey,pongURL] = data.split(" ")
        sessionKey(() => sessionStorage.setItem("sessionKey", connectionKey))
        const loadKey = state.loadKey || connectionKey
        localStorage.setItem(loadKeyForSession(), loadKey)
        return pong({...state,pongURL,connectionKey,loadKey})
    }
    const ping = data => state => {
        //console.log("ping: "+data)
        if(localStorage.getItem(loadKeyForSession()) !== (state.loadKey || never())) { // tab was refreshed/duplicated
            sessionStorage.clear()
            location.reload()
            return state
        } else if((state.connectionKey || never()) === data) return pong(state) // was not reconnected
    }
    const checkSend = transformNested("toSend",toSend => {
        const snd = message => {
            snd(message.prev)
            send(message.url, message.options)
        }
        if(toSend) snd(toSend)
        return null
    })
    const addSend = (url,inHeaders) => state => { //todo: contron message delivery at server
        const nextMessageIndex = (state.nextMessageIndex||0) + 1
        const headers = {
            ...inHeaders,
            "X-r-session": sessionKey(never),
            "X-r-index": nextMessageIndex
        }
        const prev = state.toSend
        const options = {method:"post", headers}
        const toSend = {url, options, prev}
        return ({...state, nextMessageIndex, toSend})
    }

    const init = transformNested("addSend", v => v || addSend)

    const relocateHash = data => state => {
        location.href = "#"+data
        return state
    }
    const checkHash = state => state.wasLocation === location.href ? state :
            pong({...state, wasLocation: location.href })

    ////
    const isStateClosed = v => v === 2
    const checkOK = state => !state.eventSource ? state :
            isStateClosed(state.eventSource.readyState) ? state :
            { ...state, eventSourceLastOK: Date.now() }

    const checkClose = state => {
        if(!state.eventSource || Date.now() - (state.eventSourceLastOK||0) < reconnectTimeout) return state
        state.eventSource.close();
        return ({...state, eventSource: null})
    }
    const checkCreate = state => {
        if(state.eventSource) return state
        const eventSource = createEventSource()
        receiversList.concat({connect,ping,relocateHash}).forEach(
            handlerMap => Object.keys(handlerMap).forEach(
                handlerName => eventSource.addEventListener(handlerName,
                    event => state.modify(chain([handlerMap[handlerName](event.data),checkSend]))
                )
            )
        )
        return ({...state, eventSource})
    }

    const checkActivate = chain([init,checkOK,checkClose,checkCreate,checkHash,checkSend])

    return ({checkActivate})
}
