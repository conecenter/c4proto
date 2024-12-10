// @ts-check
import { createElement, useCallback, useState, } from "react"
import {patchFromValue,ctxToPath, assertNever}    from "../main/util.js"

/********* sync input *********************************************************/

const eventToPatch = (e) => ({headers: e.target.headers, value: e.target.value, skipByPath: true, retry: true})

function useSyncInput(patches,enqueuePatch,incomingValue,deferSend){
    const [lastPatch,setLastPatch] = useState()
    const defer = deferSend(!!lastPatch)
    const onChange = useCallback(event => {
        const patch = eventToPatch(event)
        enqueuePatch({ ...patch, headers: {...patch.headers,"x-r-changing":"1"}, defer})
        setLastPatch(patch)
    },[enqueuePatch,defer])
    const onBlur = useCallback(event => {
        const replacingPatch = event && event.replaceLastPatch && eventToPatch(event)
        setLastPatch(wasLastPatch=>{
            if(wasLastPatch) enqueuePatch(replacingPatch || wasLastPatch)
            return undefined
        })
    },[enqueuePatch])
    // x-r-changing is not the same as props.changing
    //   x-r-changing -- not blur (not final patch)
    //   props.changing -- not sync-ed with server

    // this effect is not ok: incomingValue can leave the same;
    // ? see if wasLastPatch.value in patches
    // or: send blur w/o value to sub-identity; changing = patch && "1" || props.changing
    //    useEffect(()=>{
    //        setLastPatch(wasLastPatch => wasLastPatch && wasLastPatch.value === incomingValue ? wasLastPatch : undefined)
    //    },[incomingValue])
    //
    // replacingPatch - incomingValue can be different then in lastPatch && onBlur might still be needed to signal end of input

    const patch = patches.slice(-1).map(({value})=>({value}))[0]
    const value = patch ? patch.value : incomingValue
    const changing = patch ? "1" : undefined // patch || lastPatch
    return ({value,changing,onChange,onBlur})
}


/********* traverse ***********************************************************/

export const CreateNode = ({transforms,useBranch,useSync}) => {
    const SyncInput = ({identity,constr,value,onChange,...props}) => {
        const {deferSend} = onChange
        const [patches,enqueuePatch] = useSync(identity)
        const patch = useSyncInput(patches,enqueuePatch,value,deferSend)
        return createElement(constr, {...props, ...patch})
    }
    const SyncElement = ({identity,transPairs,constr,at}) => {
        const branchContext = useBranch()
        const ctx = {identity,branchContext}
        const changes = Object.fromEntries(transPairs.map(([k,t])=>[k,t(ctx)]))
        if(changes.onChange) return createElement(SyncInput, {identity,constr,...at,...changes})
        return createElement(constr, {...at,...changes})
    }
    return ({tp,...at}) => {
        const constr = transforms.tp[at.tp]
        if("identity" in at) return constr ? createElement(constr,at) : at
        //legacy:
        const transPairs = Object.keys(at).map(key=>{
            const value = at[key]
            const trans = transforms[key]
            const handler = trans && value && trans[value]
            return handler && [key, handler]
        }).filter(i=>i)
        const nAt = {at, children: at.content, ...at}
        if(transPairs.length > 0) return createElement(SyncElement, {...nAt,transPairs,constr:constr||tp})
        return createElement(constr||tp, nAt)
    }
}

/******************************************************************************/

export function VDomAttributes(sender){
    const onClick = ({"sendThen": ctx => event => { sender.send(ctx,patchFromValue("")) }}) //react gives some warning on stopPropagation
    const onChange = { "local": ctx => ch => true, "send": ctx => ch => false, "send_first": ctx => ch => changing }
    const ctx = { "ctx": ctx => ctx }
    const path = { "I": ctxToPath(ctx.identity) }
    const transforms = {onClick,onChange,ref,ctx,tp,path,identity}
    return ({transforms})
}

export const VDomSender = () => ({
    send: (ctx, options) => ctx.branchContext.enqueue(ctx.identity,options)
})

export const Feedback = () => ({
    send: ({url,options}) => fetch(url,options)
})

export const CanvasManager = (useCanvas) => {
    const Canvas = props => {
        const [parentNode, ref] = useState()
        const style = useCanvas({...props, parentNode})
        return createElement("div",{ style, ref })
    }
    return {components:{Canvas}}
}

const splitFirst = (by,data) => {
    const i = data.indexOf(by)
    return [data.substring(0,i), data.substring(i+1)]
}
export const MessageReceiver = receivers => value => {
    const [k,v] = splitFirst("\n", value)
    receivers[k] && receivers[k](v)
}

export const mergeAll = l => {
	l.flatMap(h=>Object.keys(h)).toSorted().forEach((k,i,a) => k===a[i+1] && assertNever(k))
	return Object.assign({}, ...l)
}




export const activate = ({transforms, checkActivate, useBranch, useSync}) => {
    
    
    
    


    const createNode = CreateNode({transforms, useBranch, useSync})
}