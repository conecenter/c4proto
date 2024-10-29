

let lastMessageIndex = 0

// todo: create
export function VDomSender(rawSender,sessionKey,branchKey,reloadKey){
    // in branch root state: x-r-reload x-r-index

    const ctxToPath = ctx => !ctx ? "" : ctxToPath(ctx.parent) + (ctx.key ? "/"+ctx.key : "")
    const send = (ctx, target) => {
        const headers = {
            ...target.headers,
            "x-r-session": sessionKey,
            "x-r-branch": branchKey,
            "x-r-vdom-path": ctxToPath(ctx),
            "x-r-reload": reloadKey,
            "x-r-index": ++lastMessageIndex,
            "x-r-alien-date": Date.now(),
        }
        const skipByPath = that => that.options.headers["x-r-vdom-path"] === headers["x-r-vdom-path"]
        return feedback.send({
            url: "/connection",
            options: { headers, body: target.value, method: "post" },
            defer: target.defer,
            skip: target.skipByPath && skipByPath,
            retry: target.retry //vdom-changes are more or less idempotent and can be retried
        })
        return lastMessageIndex
    }
    return ({send, busyFor: feedback.busyFor})
}
const withNext = f => (item,index,list) => f(item,list[index+1])
//non-branch messages was queue-d by url
function Sender(fetch,setTimeout){
    let defer = []
    let queue = []
    let busy = null
    let busyFrom = null
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
            .then(resp => resp.ok, err => false)
            .then(ok=>{
                if(ok || !busy.retry){
                    queue = queue.filter(item=>item!==busy)
                    activate()
                }
                else setTimeout(()=>activate(),1000)
            })
        busyFrom = busy && (busyFrom || Date.now())
    }
    function busyFor(){ return busyFrom ? Date.now() - busyFrom : 0 }
    function enqueue(message){
        defer = [...defer,message]
    }
    function flush(){
        if(defer.length===0) return;
        queue = [...queue,...defer].filter(withNext((a,b)=>!(a.skip && b && a.skip(b))))
        defer = []
        if(!busy) activate()
    }
    function send(message){
        enqueue(message)
        if(!message.defer) flush()
    }
    return ({send,busyFor})
}
