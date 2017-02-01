
function ctxToArray(ctx,res){
    if(ctx){
        ctxToArray(ctx.parent, res)
        if(ctx.key) res.push(ctx.key)
    }
    return res
}

function rootCtx(ctx){ return ctx.parent ? rootCtx(ctx.parent) : ctx }

export default function VDomChanges(sender, DiffPrepare){
    const changes = {}
    const set = (ctx,value) => {
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
            if(!sent) sent = sender.send(ctx,"change",value)
        }
        const ack = (branchKey,index) => sent &&
            branchKey === sent["X-r-branch"] &&
            parseInt(index) >= parseInt(sent["X-r-index"]) &&
            clear();
        const clear = () => {
            const diff = DiffPrepare(rCtx.localState)
            diff.jump(path.slice(0,-1))
            diff.addIfChanged("at", {}) //fix if resets alien props
            diff.apply()
            console.log("input-change removed")
            delete changes[path_str]
        }
        changes[path_str] = {send,ack}
    }
    const sendDeferred = 
        () => Object.keys(changes).forEach(path_str=>changes[path_str].send())

    const onChange = {
        "local": ctx => event => set(ctx, event.target.value),
        "send": ctx => event => { set(ctx, event.target.value); sendDeferred() }
    }
    const onBlur = {
        "send": ctx => event => sendDeferred()
    }
    const onCheck={
        "send": ctx => event => { set(ctx, event.target.checked?"Y":""); sendDeferred() }
    }
    const ackChange = data => {
        const [branchKey,indexStr] = data.split(" ")
        Object.keys(changes).forEach(path_str=>changes[path_str].ack(branchKey,indexStr))
    }

    const transforms = {onChange,onBlur,onCheck}
    const receivers = {ackChange}
    return ({transforms,receivers})
}