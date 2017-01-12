
function ctxToArray(ctx,res){
    if(ctx){
        ctxToArray(ctx.parent, res)
        if(ctx.key) res.push(ctx.key)
    }
    return res
}

function rootCtx(ctx){ return ctx.parent ? rootCtx(ctx.parent) : ctx }

export default function InputChanges(sender, DiffPrepare){
    const changes = {}
    const set = (ctx,value,now) => {
        const path = ctxToArray(ctx,[])
        const rCtx = rootCtx(ctx)

        const path_str = rCtx.branchKey + "/" + path.join("/")
        const diff = DiffPrepare(rCtx.localState)
        diff.jump(path)
        diff.addIfChanged("value", value)
        diff.apply()
        console.log("input-change added")
        var sent = null
        const send = () => { 
            if(!sent) sent = sender.send(ctx, "change", value) 
        }
        const ack = aCtx => !sent ? null :
            rootCtx(aCtx).branchKey !== rCtx.branchKey ? null :
            aCtx.value[1] !== sent["X-r-connection"] ? clear() : //old agent index
            parseInt(aCtx.value[2]) < sent["X-r-index"] ? null : clear();
        const clear = () => {
            const diff = DiffPrepare(rCtx.localState)
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
    //rootCtx(aCtx).branchKey, aCtx.value[1], parseInt(aCtx.value[2])
        ctx => Object.keys(changes).forEach(path_str=>changes[path_str].ack(ctx))
    
    const onChange = {
        "local": ctx => event => set(ctx, event.target.value, false),
        "send": ctx => event => set(ctx, event.target.value, true)
    }
    const onBlur = {
        "send": ctx => event => sendDeferred()
    }
    const ackMessage = {
        "ackMessage": ctx => { ack(ctx); return "" }
    }
    const transforms = {onChange,onBlur,ackMessage}
    
    return ({transforms})
}