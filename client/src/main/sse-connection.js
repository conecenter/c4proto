
// functional mostly

import {chain,addSend,connectionProp} from "../main/util"

export default function SSEConnection({createEventSource,receiversList,checkActivate,reconnectTimeout,localStorage,sessionStorage,location,send}){
    const never = () => { throw ["not ready"] }
    const pong = connection => {
        const url = connection.pongURL || never()
        const headers = {
            "X-r-connection": connection.connectionKey || never(),
            "X-r-location": location+""
        }
        const options = ({headers})
        return addSend({url,options})(connection)
    }
    const sessionKey = orDo => sessionStorage.getItem("sessionKey") || orDo()
    const loadKeyForSession = () => "loadKeyForSession-" + sessionKey(never)
    const connect = data => connectionProp.modify(connection => {
        //console.log("conn: "+data)
        const [connectionKey,pongURL] = data.split(" ")
        sessionKey(() => sessionStorage.setItem("sessionKey", connectionKey))
        const loadKey = connection.loadKey || connectionKey
        localStorage.setItem(loadKeyForSession(), loadKey)
        return pong({...connection,pongURL,connectionKey,loadKey})
    })
    const ping = data => connectionProp.modify(connection => {
        //console.log("ping: "+data)
        if(localStorage.getItem(loadKeyForSession()) !== (connection.loadKey || never())) { // tab was refreshed/duplicated
            sessionStorage.clear()
            location.reload()
            return connection
        } else if((connection.connectionKey || never()) === data) return pong(connection) // was not reconnected
    })

    const checkSend = connectionProp.modify(connection => { //todo: control message delivery at server
        connection.toSend.forEach(message=>{
            const [url,options] = message(sessionKey(never))
            send(message.url, message.options)
        })
        return ({...connection,toSend:[]})
    })

    const relocateHash = data => connectionProp.modify(connection => {
        location.href = "#"+data
        return connection
    })
    const checkHash = connectionProp.modify(
        connection => connection.wasLocation === location.href ?
            connection : pong({...connection, wasLocation: location.href })
    )
    ////
    const isStateClosed = v => v === 2
    const checkOK = connectionProp.modify(
        connection => !connection.eventSource ? connection :
            isStateClosed(connection.eventSource.readyState) ? connection :
            { ...connection, eventSourceLastOK: Date.now() }
    )
    const checkClose = connectionProp.modify(connection => {
        if(!connection.eventSource) return connection
        if(Date.now() - (connection.eventSourceLastOK||0) < reconnectTimeout) return connection
        connection.eventSource.close();
        return ({...connection, eventSource: null})
    })
    const checkCreate = state => connectionProp.modify(connection => {
        if(connection.eventSource) return connection
        const eventSource = createEventSource()
        receiversList.concat({connect,ping,relocateHash}).forEach(
            handlerMap => Object.keys(handlerMap).forEach(
                handlerName => eventSource.addEventListener(handlerName, state.toListener(
                    event => chain([handlerMap[handlerName](event.data),checkSend])
                ))
            )
        )
        return ({...connection, eventSource})
    })(state)

    const outCheckActivate = chain([checkActivate,checkOK,checkClose,checkCreate,checkHash,checkSend])

    return ({checkActivate:outCheckActivate})
}
