
import {merger,splitFirst,spreadAll}    from "../main/util"
import {ifInputsChanged} from "../main/vdom-util"
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

    const findRootParent = ifInputsChanged(log)("rootParentFrom", {getRootElement:1}, changed => state => {
        const get = state.getRootElement
        const rootParentElement = get && get()
        return get && !rootParentElement ? state : {...changed(state), rootParentElement}
    })

    const findFrameParent = ifInputsChanged(log)("frameParentFrom", {seedByKey:1}, changed => state => {
        const seeds = Object.keys(state.seedByKey||{}).map(k=>state.seedByKey[k]).filter(f=>f.element)
        const seed = seeds.length === 1 ? seeds[0] : null
        const frameElement = seed && seed.element
        const fontSize = seed && seed.fontSize
        const contentWindow = frameElement && frameElement.contentWindow
        const body = contentWindow && contentWindow.document.body
        const frameParentElement = body && body.id && body
        return frameElement && !frameParentElement ? state : {...changed(state), frameParentElement, fontSize}
    })

    const setupRootElement = ifInputsChanged(log)("rootElementFrom", {rootParentElement:1,frameParentElement:1}, changed => state => {
        const parentNode = state.rootParentElement || state.frameParentElement
        const was = parentNode && parentNode.c4rootNativeElement
        if(was) parentNode.removeChild(was)
        const rootNativeElement = parentNode && parentNode.ownerDocument.createElement("span")
        if(rootNativeElement) parentNode.appendChild(parentNode.c4rootNativeElement = rootNativeElement)
        return {...changed(state), rootNativeElement}
    })

    const setupRootStyle = ifInputsChanged(log)("rootStyleFrom", {rootNativeElement:1,fontSize:1}, changed => state => {
        if(state.rootNativeElement)
            state.rootNativeElement.style.fontSize = state.fontSize || ""
        return changed(state)
    })

    const merging = ifInputsChanged(log)("mergedFrom", {incoming:1,local:1}, changed => state => {
        const merge = merger((l,r)=>r)
        const merged = !state.incoming ? null :
            Object.keys(state.local||{}).sort()
            .reduce((m,k)=>merge(m,state.local[k].patch), state.incoming)
        return {...changed(state), merged}
    })

    const rendering = ifInputsChanged(log)("renderedFrom", {merged:1,rootNativeElement:1}, changed => state => {
        //if(state.rootNativeElement !== was.rootNativeElement && was.rootNativeElement)
        //    ReactDOM.unmountComponentAtNode(was.rootNativeElement)
        if(state.merged && state.rootNativeElement){
            const rootVirtualElement = React.createElement(Traverse,state.merged)
            ReactDOM.render(rootVirtualElement, state.rootNativeElement)
        }
        return changed(state)
    })

    const checkActivateBranch = chain([
        findRootParent,
        findFrameParent,
        setupRootElement,
        setupRootStyle,
        merging,
        rendering
    ])
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
        if(rKey) modify("BRANCHES",branchByKey.one(rKey, checkUpdate({getRootElement})))
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

const seedByKey = dictKeys(f=>({seedByKey:f}))

const checkUpdate = changes => state => (
    Object.keys(changes).every(k=>state && state[k]===changes[k]) ?
        state : {...state,...changes}
)

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
    const seed = ctx => element => {
        const rCtx = rootCtx(ctx)
        const brPath = rCtx.branchKey + ctxToPath(ctx)
        const branchKey = ctx.value[1]
        const fontSize = element && element.style.fontSize
        rCtx.modify("SEED",branchByKey.one(branchKey, seedByKey.one(brPath,
          checkUpdate({element,fontSize})
        )))
    }
    const root = ctx => parentNode => {}
    const ref = ({seed,root})
    const ctx = { ctx: ctx => ctx }
    const transforms = {onClick,onChange,onBlur,ref,ctx}
    return ({transforms})
}
