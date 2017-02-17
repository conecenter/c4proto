
export function rootCtx(ctx){ return ctx.parent ? rootCtx(ctx.parent) : ctx }

export function ctxToPath(ctx){
    return !ctx ? "" : ctxToPath(ctx.parent) + (ctx.key ? "/"+ctx.key : "")
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