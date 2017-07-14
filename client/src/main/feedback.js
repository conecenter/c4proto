
let loadKeyState, connectionState;
let nextMessageIndex = 0

export default function Feedback(localStorage,sessionStorage,location,fetch){

    function never(){ throw ["not ready"] }
    function pong(){
        send({
            url: getConnectionState(never).pongURL,
            options: {
                headers: {
                    "X-r-connection": getConnectionKey(never),
                    "X-r-location": location+""
                }
            }
        })
        //console.log("pong")
    }
    function sessionKey(orDo){ return sessionStorage.getItem("sessionKey") || orDo() }
    function getLoadKey(orDo){ return loadKeyState || orDo() }
    function loadKeyForSession(){ return "loadKeyForSession-" + sessionKey(never) }
    function getConnectionState(orDo){ return connectionState || orDo() }
    function getConnectionKey(orDo){ return getConnectionState(orDo).key || orDo() }
    function connect(data) {
        //console.log("conn: "+data)
        const [key,pongURL] = data.split(" ")
        connectionState = { key, pongURL }
        sessionKey(() => sessionStorage.setItem("sessionKey", getConnectionKey(never)))
        getLoadKey(() => { loadKeyState = getConnectionKey(never) })
        localStorage.setItem(loadKeyForSession(), getLoadKey(never))
        pong()
    }
    function ping(data) {
        //console.log("ping: "+data)
        if(localStorage.getItem(loadKeyForSession()) !== getLoadKey(never)) { // tab was refreshed/duplicated
            sessionStorage.clear()
            location.reload()
        } else if(getConnectionKey(never) === data) pong() // was not reconnected
    }
    function send(message){
        const headers = {
            ...message.options.headers,
            "X-r-session": sessionKey(never),
            "X-r-index": nextMessageIndex++
        }
        //todo: contron message delivery at server
        const options = {method:"post", ...message.options, headers}
        const response = fetch(message.url, {method:"post", ...options})
        return {...headers,response}
    }
    function relocateHash(data) {
        location.href = "#"+data
    }
    function signedIn(data) {
        sessionStorage.setItem("sessionKey",data)
        location.reload()
    }

    const receivers = {connect,ping,relocateHash,signedIn}
    return ({receivers,send,pong})
}