



export default function VDomChanges(rootCtx){
    const ctxToTransform = (ctx,skip,res) => !ctx ? res :
        ctx.key && skip>0 ? ctxToTransform(ctx.parent, skip-1, res)
        ctx.key ? ctxToTransform(ctx.parent, skip, transformNested(ctx.key,res)) :
        ctx.branchKey ? ctxToTransform(ctx.parent, skip,
            transformNested("branches",transformNested(ctx.branchKey,transformNested("local",
                res
            )))
        ) :
        ctxToTransform(ctx.parent, skip, res)

    //const changes = {}
    const set = (ctx,value) => state => { //todo
        const path = ctxToArray(ctx,[])
        const rCtx = rootCtx(ctx)
        const path_str = rCtx.branchKey + ctxToPath(ctx)



        ctxToTransform(ctx,1,at=>({...at,value}))(state)



        //console.log("input-change added")
        var sent = null
        const send = () => {
            if(!sent) sent = vDomSend(ctx,"change",value)
        }
        const ack = (branchKey,index) => sent &&
            branchKey === sent["X-r-branch"] &&
            parseInt(index) >= parseInt(sent["X-r-index"]) &&
            clear();
        const clear = () => {

            ctxToTransform(ctx,1,at=>({}))(state) //fix if resets alien props




            //console.log("input-change removed")
            delete changes[path_str]
        }
        changes[path_str] = {send,ack}
        return state
    }
    const sendDeferred = 
        state => Object.keys(changes).forEach(path_str=>changes[path_str].send()) //todo

    const onChange = {
        "local": ctx => event => set(ctx, event.target.value),
        "send": ctx => event => chain([set(ctx, event.target.value), sendDeferred])
    }
    const onBlur = {
        "send": ctx => event => sendDeferred
    }
    const onCheck={
        "send": ctx => event => chain([set(ctx, event.target.checked?"Y":""), sendDeferred])
    }
    const ackChange = (branchKey,data) => state => {
        Object.keys(changes).forEach(path_str=>changes[path_str].ack(branchKey,data))
        return state
    }

    const transforms = {onChange,onBlur,onCheck}
    const branchHandlers = {ackChange}
    return ({transforms,branchHandlers})
}