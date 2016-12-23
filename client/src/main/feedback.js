
var loadKeyState, connectionState;

function never(){ throw new Exception() }
function pong(){
    const headers = { 
        "X-r-action": "pong", 
        "X-r-session": sessionKey(never),
        "X-r-location-hash": location.hash.substr(1)
    }
    send(headers)
    console.log("pong")
}
function sessionKey(orDo){ return sessionStorage.getItem("sessionKey") || orDo() }
function getLoadKey(orDo){ return loadKeyState || orDo() }
function loadKeyForSession(){ return "loadKeyForSession-" + sessionKey(never) }
function getConnectionState(orDo){ return connectionState || orDo() }
function getConnectionKey(orDo){ return getConnectionState(orDo).key || orDo() }
function connect(data) {
    console.log("conn: "+data)
    var nextMessageIndex = 0
    connectionState = { key: data, nextMessageIndex: ()=>nextMessageIndex++ }
    sessionKey(() => sessionStorage.setItem("sessionKey", getConnectionKey(never)))
    getLoadKey(() => { loadKeyState = getConnectionKey(never) })
    localStorage.setItem(loadKeyForSession(), getLoadKey(never))
    window.onhashchange = () => pong()
    pong()
}
function ping(data) {
    console.log("ping: "+data)
    if(localStorage.getItem(loadKeyForSession()) !== getLoadKey(never)) { // tab was refreshed/duplicated
        sessionStorage.clear()
        location.reload()
    } else if(getConnectionKey(never) === data) pong() // was not reconnected
}
function send(headers){
    headers["X-r-connection"] = getConnectionKey(never)
    headers["X-r-index"] = getConnectionState(never).nextMessageIndex()
    //todo: contron message delivery at server
    fetch("/connection",{method:"post",headers})
    return headers
}
function relocateHash(data) { 
    document.location.href = "#"+data 
}
export default function(){
    const receivers = {connect,ping,relocateHash}
    return ({receivers,send}) 
}