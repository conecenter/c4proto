
let nextMessageIndex = 0
const senders = {}

export default function Feedback(sessionStorage,location,fetch,setTimeout){
    const pong = (allowNoSession,modify) => state => {
        if(!state.connectionKey) return state;
        send({
            url: state.pongURL,
            options: {
                headers: {
                    "x-r-connection": state.connectionKey,
                    "x-r-location": location+""
                }
            },
            skip: that=>true,
            allowNoSession
        },modify)
        return state
    }
    const connect = (data,modify) => {
        const [connectionKey,pongURL] = data.split(" ")
        modify("CONNECT", state => pong(true,modify)({...state,connectionKey,pongURL}))
    }
    const ping = (data,modify) => modify("PING",
        state => state.connectionKey === data ? pong(false,modify)(state) : state// was not reconnected
    )
    function never(){ throw ["not ready"] }
    const send = (message,modify) => {
        const headers = {
            ...message.options.headers,
            "x-r-session": sessionStorage.getItem("sessionKey") || (message.allowNoSession?"":never()),
            "x-r-index": nextMessageIndex++,
            "x-r-alien-date": Date.now()
        }
        const options = {method:"post", ...message.options, headers}
        const qKey = headers["x-r-branch"] || message.url
        const sender = senders[qKey] || (senders[qKey] = Sender(fetch,setTimeout))
        const onComplete = resp => {
            if(resp.headers.has("x-r-set-session")){
                const sessionKey = resp.headers.get("x-r-set-session")
                if(!sessionKey && sessionStorage.getItem("sessionAt")-0>Date.now()-3000) return resp.ok; //may be: another sender can change global sessionKey during this request; or new session was not found (there's no read-after-write here)
                sessionStorage.clear()
                if(sessionKey){
                    sessionStorage.setItem("sessionKey",sessionKey)
                    sessionStorage.setItem("sessionAt",Date.now())
                }
                modify("SESSION_SET",pong(true,modify))
            }
            return resp.ok
        }
        sender.send({...message, options, onComplete})
        return headers
    }
    function relocateHash(data) {
        location.href = "#"+data
    }
    const receivers = {connect,ping,relocateHash}
    return ({receivers,send})
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
            .then(busy.onComplete,err=>false)
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
