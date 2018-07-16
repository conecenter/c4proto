
import {merger,splitFirst,spreadAll}    from "../main/util"
import React           from 'react'
import ReactDOM        from 'react-dom'
import update          from 'immutability-helper'

import {dictKeys,branchByKey,rootCtx,ctxToPath,chain} from "../main/vdom-util"

const localByKey = dictKeys(f=>({local:f}))

function ctxToPatch(ctx,res){
    return !ctx ? res : ctxToPatch(ctx.parent, ctx.key ? {[ctx.key]:res} : res)
}
const setDeferred = (ctx,target) => {
    const rCtx = rootCtx(ctx)
    const path = ctxToPath(ctx)
    const patch = ctxToPatch(ctx, {value: target.value})
    const change = ({ctx,target,patch})
    rCtx.modify("CHANGE_SET",branchByKey.one(rCtx.branchKey,localByKey.one(path, st => change)))
}
const sendDeferred = (sender,ctx) => {
    const rCtx = rootCtx(ctx)
    rCtx.modify("CHANGE_SEND",branchByKey.all(localByKey.all(st => {
        return st.sent ? st : {...st, sent: sender.send(st.ctx,st.target)} // todo fix bug, ask aku
    })))
}

class Traverse extends React.PureComponent{
    render(){
        const props = this.props
        const at = props.at
        const content =
            at.content && at.content[0] === "rawMerge" ? props :
            props.chl ? props.chl.map(key => React.createElement(Traverse, props[key])) :
            at.content || null
        return React.createElement(at.tp, at, content)
    }
}

//todo branch LIFE
const setupRootElement = state => {
    if(state.rootNativeElement) return state
    const get = state.getRootElement
    const parentNode = get && get()
    if(!parentNode) return state
    const was = parentNode.c4rootNativeElement
    if(was) parentNode.removeChild(was)
    const rootNativeElement = parentNode.ownerDocument.createElement("span")
    parentNode.appendChild(parentNode.c4rootNativeElement = rootNativeElement)
    return {...state, rootNativeElement}
}

const merging = state => {
    if(!state.incoming) return state
    const was = state.mergedFrom || {}
    if(state.incoming===was.incoming && state.local===was.local) return state
    const merge = merger((l,r)=>r)
    const merged = Object.keys(state.local||{}).sort()
        .reduce((m,k)=>merge(m,state.local[k].patch), state.incoming)
    return {...state, mergedFrom: {incoming: state.incoming, local: state.local}, merged}
}

const rendering = state => {
    if(!state.merged) return state
    const was = state.renderedFrom || {}
    if(state.merged === was.merged && state.rootNativeElement === was.rootNativeElement) return state
    const rootVirtualElement = React.createElement(Traverse,state.merged)
    ReactDOM.render(rootVirtualElement, state.rootNativeElement)
    return {...state, renderedFrom: {merged: state.merged, rootNativeElement: state.rootNativeElement} }
}

const getLastKey = ctx => ctx && (ctx.key || getLastKey(ctx.parent))
function setupIncomingDiff(by,content,parent) {
    const {isInSet} = parent
    const setKey = isInSet && content["at"] && getLastKey(parent)
    const changes = Object.keys(content).map(key=>{
        const value = content[key]
        const trans = by[key]
        const handler = trans && value && (trans[value] || trans[value[0]])
        const will =
            handler ?
                (key==="tp" ? handler : handler({ value, parent, isInSet })) :
            key.substring(0,1)===":" || key === "at" ?
                setupIncomingDiff(by,value,{ key, parent, isInSet }) :
            key === "$set" ?
                setupIncomingDiff(by,value,{ parent, isInSet: true }) :
            value
        return value === will ? null : ({ [key]: will })
    }).concat(setKey && {key:setKey}).filter(i=>i)

    return changes.length>0 ? spreadAll(content, ...changes) : content
}

export function VDomCore(log,activeTransforms,getRootElement){

    const checkActivateBranch = chain([setupRootElement,merging,rendering])
    const checkActivate = modify => modify("FRAME",branchByKey.all(checkActivateBranch))

    const showDiff = (data,modify) => {
        const [branchKey,body] = splitFirst(" ", data)
        const value = JSON.parse(body)
        const ctx = {branchKey, modify}
        const nValue = setupIncomingDiff(activeTransforms,value,ctx)
        modify("SHOW_DIFF",branchByKey.one(branchKey, state => {
            const was = state && state.incoming
            if(!was && !value["$set"]) return null
            const incoming = update(was || {}, nValue)
            return {...state,incoming}
        }))
    }

    const branches = (data,modify) => {
        const rKey = data.match(/^[^,;]+/)
        if(rKey) modify("BRANCHES",branchByKey.one(rKey, v => v.getRootElement ? v : {...v,getRootElement}))
    }

    const ackChange = (data,modify) => {
        const [branchKey,body] = splitFirst(" ", data)
        const index = parseInt(body)
        modify("ACK_CHANGE",branchByKey.one(branchKey,localByKey.all(st => {
            return st.sent && index >= parseInt(st.sent["X-r-index"]) ? null : st
        })))
    }

    const receivers = ({branches,showDiff,ackChange})
    return ({receivers,checkActivate})
}

const getRootElementFrame = el => () => {
    const contentWindow = el && el.contentWindow
    const body = contentWindow && contentWindow.document.body
    return body && body.id && body
}

export function VDomAttributes(sender){
    const sendThen = ctx => event => sender.send(ctx,{value:""})
    const onClick = ({/*send,*/sendThen}) //react gives some warning on stopPropagation
    const onChange = {
        "local": ctx => event => setDeferred(ctx, event.target),
        "send": ctx => event => { setDeferred(ctx, event.target); sendDeferred(sender, ctx) } // todo no resize anti-dos
    }
    const onBlur = {
        "send": ctx => event => sendDeferred(sender, ctx)
    }
    const seed = ctx => parentNode => {
        const rCtx = rootCtx(ctx)
        const branchKey = ctx.value[1]
        const getRootElement = getRootElementFrame(parentNode)
        rCtx.modify("SEED",branchByKey.one(branchKey, state=>({...state, getRootElement})))
    }
    const ref = ({seed})
    const ctx = { ctx: ctx => ctx }
    const transforms = {onClick,onChange,onBlur,ref,ctx}
    return ({transforms})
}