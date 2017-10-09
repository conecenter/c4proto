
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
    const set = (ctx,target) => {
        const path = ctxToArray(ctx,[])
        const rCtx = rootCtx(ctx)
        const path_str = rCtx.branchKey + "/" + path.join("/")
        const diff = DiffPrepare(rCtx.localState)
        diff.jump(path)
        diff.addIfChanged("value", target.value)
        diff.apply()
        //console.log("input-change added")
        let sent = null
        const send = () => {
            if(!sent) sent = sender.send(ctx,target) // todo fix bug, ask aku
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
            //console.log("input-change removed")
            delete changes[path_str]
        }
        changes[path_str] = {send,ack}
    }
    const sendDeferred = 
        () => Object.keys(changes).forEach(path_str=>changes[path_str].send())

    const onChange = {
        "local": ctx => event => set(ctx, event.target),
        "send": ctx => event => { set(ctx, event.target); sendDeferred() } // todo no resize anti-dos
    }
    const onBlur = {
        "send": ctx => event => sendDeferred()
    }
    const ackChange = data => state => {
        Object.keys(changes).forEach(path_str=>changes[path_str].ack(state.branchKey,data))
        return state
    }

    const transforms = {onChange,onBlur}
    return ({transforms,ackChange})
}