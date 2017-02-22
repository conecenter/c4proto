
// functional

export function rootCtx(ctx){ return ctx.parent ? rootCtx(ctx.parent) : ctx }

export function ctxToPath(ctx){
    return !ctx ? "" : ctxToPath(ctx.parent) + (ctx.key ? "/"+ctx.key : "")
}

export const ctxMessage = (ctx, action, value) => ({ // todo: may be we need a queue to be sure server will receive messages in right order
    body: value,
    headers: {
        "X-r-action": action,
        "X-r-branch": rootCtx(ctx).branchKey,
        "X-r-vdom-path": ctxToPath(ctx)
    }
})