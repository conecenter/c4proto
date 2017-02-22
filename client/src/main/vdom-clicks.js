
// functional

import {branchSend} from "../main/util"
import {ctxMessage} from "../main/vdom-util"

export default function VDomClicks(){
    const sendThen = ctx => event => branchSend(ctxMessage(ctx,"click",""))
    const onClick = ({/*send,*/sendThen}) //react gives some warning on stopPropagation
    const transforms = ({onClick})
    return ({transforms})
}
