



export default function VDomChanges(rootCtx,transformNested){
    const ctxToTransform = (ctx,skip,res) => !ctx ? res :
        ctx.key && skip>0 ? ctxToTransform(ctx.parent, skip-1, res) :
        ctx.key ? ctxToTransform(ctx.parent, skip, transformNested(ctx.key,res)) :
        ctx.branchKey ? ctxToTransform(ctx.parent, skip,
            transformNested("branches",transformNested(ctx.branchKey,transformNested("local",
                res
            )))
        ) :
        ctxToTransform(ctx.parent, skip, res)
    const ctxToChangesTransform = (ctx,change,at) => chain([
        at ? ctxToTransform(ctx,1,v=>at) : st=>st, //fix if resets alien props
        transformNested("branches",transformNested(rootCtx(ctx).branchKey,transformNested("changes",transformNested(ctxToPath(ctx),
           v=>change
        ))))
    ])

    const set = (ctx,value) => ctxToChangesTransform(ctx,{ctx},{value})
    const send = change => rootCtx(change.ctx).send(ctx,"change",change.value)
    const setSent = change => state => {
        const sent = { index: state.lastMessageIndex }
        return ctxToChangesTransform(change.ctx,{ctx,sent},null)(state)
    }
    const changes = branch => Object.values(branch.changes||{})
    const notSent = change => change && !change.sent
    const sendSetSent = change => chain([send(change),setSent(change)])
    const sendBranch = branch => chain(changes(branch).filter(notSent).map(sendSetSent))
    const isToAck = index => change => change && change.sent && index >= change.sent.index
    const clear = change => ctxToChangesTransform(change.ctx,null,{})
    const ackBranch = (branch,index) => chain(changes(branch).filter(isToAck(index)).map(clear))

    const sendDeferred = state => chain(Object.values(state.branches).map(sendBranch))(state)
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
        const branch = state.branches[branchKey] || {}
        const index = parseInt(data)
        return ackBranch(branch,index)(state)
    }

    const transforms = {onChange,onBlur,onCheck}
    const branchHandlers = {ackChange}
    return ({transforms,branchHandlers})
}