
import {merger,splitFirst,spreadAll}    from "../main/util"
import {ifInputsChanged} from "../main/vdom-util"
import React           from 'react'
import ReactDOM        from 'react-dom'
import update          from 'immutability-helper'

import {dictKeys,branchByKey,rootCtx,ctxToPath,chain,deleted} from "../main/vdom-util"

const localByKey = dictKeys(f=>({local:f}))

function ctxToPatch(ctx,res){
    return !ctx ? res : ctxToPatch(ctx.parent, ctx.key ? {[ctx.key]:res} : res)
}
const setDeferred = (ctx,target) => {
    const rCtx = rootCtx(ctx)
    const path = ctxToPath(ctx)
    const patch = ctxToPatch(ctx, { value: target.value, changing: true })
    const change = ({ctx,target:{...target,skipByPath:true},patch})
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

const oValues = o => Object.keys(o||{}).sort().map(k=>o[k])

export function VDomCore(log,activeTransforms,getRootElement){
        
    const joinSeeds = ifInputsChanged(log)("seedsFrom", {branchByKey:1}, changed => state => {
      const seedByKey = spreadAll(...oValues(state.branchByKey).map(brSt=>
          spreadAll(...oValues(brSt.seedByKey).filter(seed=>seed.element).map(seed=>({[seed.branchKey]:seed})))
      ))
      return branchByKey.all(brSt=>checkUpdate({seed:seedByKey[brSt.branchKey]})(brSt))(changed(state))
    })
    
    const joinBranches = ifInputsChanged(log)("branchesFrom", {branchByKey:1,activeBranchesStr:1}, changed => state => {
        return branchByKey.all(brSt=>{
            const isRoot  = brSt.branchKey && (state.activeBranchesStr||"").startsWith(brSt.branchKey)
            const isActive = brSt.branchKey && (state.activeBranchesStr||"").includes(brSt.branchKey) 
            return checkUpdate({ isActive, isRoot })(brSt)
        })(changed(state))
    })
    
    const findRootParent = ifInputsChanged(log)("rootParentFrom", {isRoot:1}, changed => state => {
        if(!state.isRoot) return changed(state)
        const rootParentElement = getRootElement()
        if(!rootParentElement) return state
        return {...changed(state), rootParentElement}
    })

    const findFrameParent = ifInputsChanged(log)("frameParentFrom", {seed:1}, changed => state => {
        const seed = state.seed
        const frameElement = seed && seed.element
        const fontSize = seed && seed.fontSize
        const contentWindow = frameElement && frameElement.contentWindow
        const body = contentWindow && contentWindow.document.body
        const frameParentElement = body && body.id && body
        if(frameElement && !frameParentElement) return state;
        if(frameParentElement)
            frameParentElement.style.fontSize = fontSize || ""
        return ({...changed(state), frameParentElement})
    })

    const setupRootElement = ifInputsChanged(log)("rootElementFrom", {rootParentElement:1,frameParentElement:1}, changed => state => {
        const parentNode = state.rootParentElement || state.frameParentElement
        const rootNativeElement = state.rootNativeElement || parentNode && parentNode.ownerDocument.createElement("span")
        if(rootNativeElement && rootNativeElement.parentElement !== parentNode) {
            if(parentNode) parentNode.appendChild(rootNativeElement) 
            else rootNativeElement.parentElement.removeChild(rootNativeElement)
        }
        return {...changed(state), rootNativeElement}
    })

    const merging = ifInputsChanged(log)("mergedFrom", {incoming:1,local:1}, changed => state => {
        const merge = merger((l,r)=>r)
        const merged = !state.incoming ? null :
            oValues(state.local).reduce((m,v)=>merge(m,v.patch), state.incoming)
        return {...changed(state), merged}
    })

    const rendering = ifInputsChanged(log)("renderedFrom", {merged:1,rootNativeElement:1}, changed => state => {
        if(state.merged && state.rootNativeElement){
            const rootVirtualElement = React.createElement(Traverse,state.merged)
            ReactDOM.render(rootVirtualElement, state.rootNativeElement)
        }
        return changed(state)
    })

    const mortality = ifInputsChanged(log)("lifeFrom", {isActive:1}, changed => state => {
        if(state.isActive) return changed(state)
        if(Date.now()-state.incomingTime < 100) return state 
        if(state.rootNativeElement) {
            ReactDOM.unmountComponentAtNode(state.rootNativeElement)
            //parentNode.removeChild(was)
        }
        return null
    })
    
    const checkActivate = modify => modify("FRAME",chain([
        joinBranches,
        joinSeeds,
        branchByKey.all(chain([
            findRootParent,
            findFrameParent,
            setupRootElement,
            merging,
            rendering,
            mortality
        ]))
    ]))

    const showDiff = (data,modify) => {
        const [branchKey,body] = splitFirst(" ", data)
        const value = JSON.parse(body)
        const ctx = {branchKey, modify}
        const nValue = setupIncomingDiff(activeTransforms,value,ctx)
        modify("SHOW_DIFF",branchByKey.one(branchKey, state => {
            const was = state && state.incoming
            if(!was && !value["$set"]) return null
            const incoming = update(was || {}, nValue)
            const incomingTime = Date.now()
            return {...state,incoming,incomingTime,branchKey}
        }))
    }

    const branches = (data,modify) => {
        modify("BRANCHES", checkUpdate({activeBranchesStr:data}))
    }

    const ackChange = (data,modify) => {
        const [branchKey,body] = splitFirst(" ", data)
        const index = parseInt(body)
        modify("ACK_CHANGE",branchByKey.one(branchKey,localByKey.all(st => {
            return st.sent && index >= parseInt(st.sent["x-r-index"]) ? null : st
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
        const path = ctxToPath(ctx)
        const branchKey = ctx.value[1]
        const fontSize = element && element.style.fontSize
        rCtx.modify("SEED",branchByKey.one(rCtx.branchKey, seedByKey.one(path,
          checkUpdate({branchKey,element,fontSize})
        )))
    }
    const noPass = {value:1}
    const ReControlledInput = React.forwardRef((prop, ref) => React.createElement("input",{
        ...deleted(noPass)(prop),
        ref: el=>{
            if(el) el.value = prop.value //todo m. b. gather, do not update dom in ref
            if(ref) ref(el)
        }
    },null))
    const ref = ({seed})
    const ctx = { ctx: ctx => ctx }
    const path = { "I": ctxToPath }
    const tp = ({ReControlledInput})
    const transforms = {onClick,onChange,onBlur,ref,ctx,tp,path}
    return ({transforms})
}
