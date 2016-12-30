
export default function Transforms(sender){
    const send = ctx => event => {
        sender.send(ctx, "click", "")
        event.stopPropagation()
    }
    const sendThen = ctx => event => sender.send(ctx, "click", "")
    const onClick = ({send,sendThen})

    const onResize={
        "send": ctx => event => sender.send(ctx, "resize", event.width)
    }
    const onCheck={
        "send": ctx => event => sender.send(ctx, "change", event.target.checked?"Y":"")
    }
    const transforms = ({onClick,onResize,onCheck})
    return ({transforms})
}
