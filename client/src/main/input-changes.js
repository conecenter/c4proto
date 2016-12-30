
export default function InputChanges(sender, vDom, DiffPrepare){
    
    const changes = {}
    const set = (ctx,value,now) => {
        const path = vDom.ctxToArray(ctx,[])
        const path_str = path.join("/")
        const diff = DiffPrepare(vDom.localState)
        diff.jump(path)
        diff.addIfChanged("value", value)
        diff.apply()
        console.log("input-change added")
        var sent = null
        const send = () => { 
            if(!sent) sent = sender.send(ctx, "change", value) 
        }
        const ack = (key,index) => !sent ? null : 
            key !== sent["X-r-connection"] ? clear() : 
            index < sent["X-r-index"] ? null : clear()
        const clear = () => {
            const diff = DiffPrepare(vDom.localState)
            diff.jump(path.slice(0,-1))
            diff.addIfChanged("at", {}) //fix if resets alien props
            diff.apply()
            console.log("input-change removed")
            delete changes[path_str]
        }
        changes[path_str] = {send,ack}
        if(now) send()
    }
    const sendDeferred = 
        () => Object.keys(changes).forEach(path_str=>changes[path_str].send())
    const ack = 
        (key, index) => Object.keys(changes).forEach(path_str=>changes[path_str].ack(key, index))
    
    const onChange = {
        "local": ctx => event => set(ctx, event.target.value, false),
        "send": ctx => event => set(ctx, event.target.value, true)
    }
    const onBlur = {
        "send": ctx => event => sendDeferred()
    }
    const ackMessage = {
        "ackMessage": ctx => { ack(ctx.value[1], parseInt(ctx.value[2])); return "" }
    }
    const transforms = {onChange,onBlur,ackMessage}
    
    return ({transforms})
}