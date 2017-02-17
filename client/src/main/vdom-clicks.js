
export default function VDomClicks(rootCtx){
    const sendThen = ctx => event => rootCtx(ctx).send(ctx,"click","")
    const onClick = ({/*send,*/sendThen}) //react gives some warning on stopPropagation
    const transforms = ({onClick})
    return ({transforms})
}
