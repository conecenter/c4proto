
// functional

import {chain,lensProp,branchProp,branchesProp,connectionProp,branchSend} from "../main/util"
import {rootCtx,ctxToPath,ctxMessage} from "../main/vdom-util"

export default function VDomChanges(){
    const localProp = lensProp("local")
    const changesProp = lensProp("changes")
    const ctxToProp = (ctx,skip,res) => (
        ctx.key && skip>0 ? ctxToProp(ctx.parent, skip-1, res) :
        ctx.key && res ? ctxToProp(ctx.parent, skip, lensProp(ctx.key).compose(res) ) :
        ctx.key ? ctxToProp(ctx.parent, skip, lensProp(ctx.key) ) :
        ctx.branchKey ? branchProp(ctx.branchKey).compose(localProp.compose(res)) :
        ctxToProp(ctx.parent, skip, res)
    )
    const ctxToChangesTransform = (ctx,change,at) => chain([
        at ? ctxToProp(ctx,1,null).set(at) : st=>st, //fix if resets alien props
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

    const sendDeferred = state => chain(Object.values(branchesProp.of(state)).map(sendBranch))(state)
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