
function rootCtx(ctx){ return ctx.parent ? rootCtx(ctx.parent) : ctx }

export default function VDomSeeds(){
    const seed = ctx => parentNode => rootCtx(ctx).modify(ctx.value[1],
        state=>({...state, parentNode})
    )
    const ref = ({seed})
    const transforms = ({ref})
    return ({transforms})
}