
export default function VDomClicks(sender){
    const sendClick = ctx => sender.send(ctx, { "X-r-action": "click" })
    const send = ctx => event => {
        sendClick(ctx)
        event.stopPropagation()
    }
    const sendThen = ctx => event => sendClick(ctx)
    const onClick = ({send,sendThen})
    const transforms = ({onClick})
    return ({transforms})
}
