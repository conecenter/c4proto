
let loadKeyState, connectionState;
let nextMessageIndex = 0
const senders = {}

export default function Feedback(localStorage,sessionStorage,location,fetch,setTimeout){

    function never(){ throw ["not ready"] }
    function pong(){
        send({
            url: getConnectionState(never).pongURL,
            options: {
                headers: {
                    "X-r-connection": getConnectionKey(never),
                    "X-r-location": location+""
                }
            },
            skip: that=>true
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
            "X-r-index": nextMessageIndex++,
            "X-r-alien-date": Date.now()
        }
        const options = {method:"post", ...message.options, headers}
        const qKey = headers["X-r-branch"] || message.url
        const sender = senders[qKey] || (senders[qKey] = Sender(fetch,setTimeout))
        sender.send({...message, options})
        return headers
    }
    function relocateHash(data) {
        location.href = "#"+data
    }
    function signedIn(data) {
        sessionStorage.setItem("sessionKey",data)
		localStorage.setItem(loadKeyForSession(), getLoadKey(never))
        location.reload()
    }

    const receivers = {connect,ping,relocateHash,signedIn}
    return ({receivers,send,pong})
}

function Sender(fetch,setTimeout){
    let queue = []
    let busy = null
    /*
    if make retry here, then it can lead to post duplication, so requires extra server deduplication stuff;
    retry is ok for idempotent messages, like input changes;
    // todo
    may be it would be better to make all server actions idempotent, but currently they are not;
    so retry is an option safely default to false
    */
    function activate(){
        busy = queue[0]
        if(busy) fetch(busy.url,busy.options)
            .then(resp=>resp.status===200,err=>false)
            .then(ok=>{
                if(ok || !busy.retry){
                    queue = queue.filter(item=>item!==busy)
                    activate()
                }
                else setTimeout(()=>activate(),1000)
            })
    }
    function send(message){
        queue = [...queue.filter(m=>!(m.skip && m.skip(message))), message]
        if(!busy) activate()
    }
    return ({send})
}
