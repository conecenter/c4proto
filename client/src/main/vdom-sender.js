
function rootCtx(ctx){ return ctx.parent ? rootCtx(ctx.parent) : ctx }

export default function VDomSender(feedback){ // todo: may be we need a queue to be sure server will receive messages in right order
    const ctxToPath =
        ctx => !ctx ? "" : ctxToPath(ctx.parent) + (ctx.key ? "/"+ctx.key : "")
    const send = (ctx, req) => feedback.send({
        ...req,
        "X-r-vdom-branch": rootCtx(ctx).branchKey,
        "X-r-vdom-path": ctxToPath(ctx)
    })
    return ({send})
}