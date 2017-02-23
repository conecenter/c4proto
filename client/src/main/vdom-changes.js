
// functional

import {chain,lensProp,branchProp,branchesActiveProp,connectionProp,branchSend,ctxToProp} from "../main/util"
import {rootCtx,ctxToPath,ctxMessage} from "../main/vdom-util"

export default function VDomChanges(){
    const changesProp = lensProp("changes")
    const ctxToChangesTransform = (ctx,change,at) => chain([
        at ? ctxToProp(ctx,null).set(at) : st=>st, //fix if resets alien props
        branchProp(rootCtx(ctx).branchKey).compose(changesProp.compose(lensProp(ctxToPath(ctx)))).set(change)
    ])

    const set = (ctx,value) => ctxToChangesTransform(ctx,{ctx},{value})
    const send = change => branchSend(ctxMessage(change.ctx,"change",change.value))
    const setSent = change => state => {
        const sent = { index: connectionProp.of(state).lastMessageIndex }
        const ctx = change.ctx
        return ctxToChangesTransform(ctx,{ctx,sent},null)(state)
    }
    const changes = branch => Object.values(branch.changes||{})
    const notSent = change => change && !change.sent
    const sendSetSent = change => chain([send(change),setSent(change)])
    const sendBranch = branch => chain(changes(branch).filter(notSent).map(sendSetSent))
    const isToAck = index => change => change && change.sent && index >= change.sent.index
    const clear = change => ctxToChangesTransform(change.ctx,null,{})
    const ackBranch = (branch,index) => chain(changes(branch).filter(isToAck(index)).map(clear))

    const sendDeferred = state => chain((branchesActiveProp.of(state)||[]).map(k=>sendBranch(branchProp(k).of(state))))(state)
    const onChange = {
        "local": ctx => event => set(ctx, event.target.value),
        "send": ctx => event => chain([set(ctx, event.target.value), sendDeferred])
    }
    const onBlur = {
        "send": ctx => event => sendDeferred
    }
    const onCheck={
        "send": ctx => event => chain([set(ctx, event.target.checked?"Y":""), sendDeferred])
    }
    const ackChange = (branchKey,data) => state => {
        const branch = branchProp(branchKey).of(state)
        return ackBranch(branch,parseInt(data))(state)
    }

    const transforms = {onChange,onBlur,onCheck}
    const branchHandlers = {ackChange}
    return ({transforms,branchHandlers})
}