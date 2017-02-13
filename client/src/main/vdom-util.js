
function rootCtx(ctx){ return ctx.parent ? rootCtx(ctx.parent) : ctx }

function ctxToPath(ctx){
    return !ctx ? "" : ctxToPath(ctx.parent) + (ctx.key ? "/"+ctx.key : "")
}

export function VDomSender(feedback,encode){ // todo: may be we need a queue to be sure server will receive messages in right order
    const send = (ctx, action, value) => feedback.send("/connection", {
        "X-r-action": action,
        "X-r-vdom-value-base64": encode(value),
        "X-r-branch": rootCtx(ctx).branchKey,
        "X-r-vdom-path": ctxToPath(ctx)
    })
    return ({send})
}

export function VDomSeeds(log){
    const seed = ctx => parentNode => {
        const rCtx = rootCtx(ctx)
        const fromKey = rCtx.branchKey + ":" + ctxToPath(ctx)
        const branchKey = ctx.value[1]
        rCtx.modify(branchKey, state=>({
            ...state,
            parentNodes:{
                ...state.parentNodes,
                [fromKey]: parentNode
            }
        }))
    }
    const ref = ({seed})
    const transforms = ({ref})
    return ({transforms})
}