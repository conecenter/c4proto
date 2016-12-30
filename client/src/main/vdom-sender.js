
export default function VDomSender(feedback){ // todo: may be we need a queue to be sure server will receive messages in right order
    const ctxToPath =
        ctx => !ctx ? "" : ctxToPath(ctx.parent) + (ctx.key ? "/"+ctx.key : "")
    const send = (ctx, action, value) => feedback.send({
        "X-r-action": action,
        "X-r-vdom-path": ctxToPath(ctx),
        "X-r-vdom-value-base64": btoa(unescape(encodeURIComponent(value)))
    })
    return ({send})
}