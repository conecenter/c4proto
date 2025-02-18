// #@ts-check
import { createElement, useCallback, useState, useEffect, useMemo, useRef } from "react"
import { patchFromValue, ctxToPath, assertNever, manageAnimationFrame } from "../main/util.ts"
import { doCreateRoot, useIsolatedFrame } from "../main/frames.ts"
import { useSessionManager } from "../main/session.ts"
import { createContext } from "react"
import { useContext } from "react"
import { useSyncRoot } from "../main/sync-root.ts"
import { LocationComponents } from "../main/location.ts"
import { ToAlienMessageComponents } from "../main/receiver.ts"
import { AckContext, UseSyncMod } from "../main/sync-hooks.ts"
import { UseCanvas } from "./canvas-manager.ts"

export const rootCtx = ctx => ctx.branchContext

/********* sync input *********************************************************/

const eventToPatch = (e) => ({headers: e.target.headers, value: e.target.value, skipByPath: true, retry: true})

function useSyncInput(patches,enqueuePatch,incomingValue,deferSend) {
    /** @type {React.MutableRefObject<import('../main/util.ts').UnsubmittedPatch | null>} */
    const lastPatch = useRef(null)
    const onChange = useCallback(event => {
        const patch = eventToPatch(event)
        const defer = deferSend(!!lastPatch.current)
        // we try to ignore `defer` later for now; 
        // it can not be used to reliably prevent sending, because random non-defer-ed event will flush queue anyway;
        // it seems to be either: responsibility of server to defer handling, or component not to enqueue before blur;
        enqueuePatch({...patch, headers: {...patch.headers,"x-r-changing":"1"}, defer})
        lastPatch.current = patch
    }, [enqueuePatch])
    const onBlur = useCallback(event => {
        const replacingPatch = event?.replaceLastPatch && eventToPatch(event)
        if (lastPatch.current) enqueuePatch(replacingPatch || lastPatch.current)
        lastPatch.current = null
    }, [enqueuePatch])
    const patch = patches.slice(-1).map(({value})=>({value}))[0]
    const value = patch ? patch.value : incomingValue
    const changing = patch ? "1" : undefined // patch || lastPatch
    return ({value,changing,onChange,onBlur})
}
/*
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
*/


/********* traverse ***********************************************************/

const CreateNode = ({transforms}) => {
    const SyncInput = ({identity,constr,value,onChange,...props}) => {
        const [patches,enqueuePatch] = useSync(identity)
        const patch = useSyncInput(patches,enqueuePatch,value,onChange)
        return createElement(constr, {...props, ...patch})
    }
    const SyncElement = ({identity,transPairs,constr,at}) => {
        const branchContext = useBranch()
        const ctx = {identity,branchContext}
        const changes = Object.fromEntries(transPairs.map(([k,t])=>[k,t(ctx)]))
        if(changes.onChange) return createElement(SyncInput, {identity,constr,...at,...changes})
        return createElement(constr, {...at,...changes})
    }
    const createNode = ({tp,...at}) => {
        if(!tp) return at
        const constr = transforms.tp[tp] || tp
        //legacy:
        const transPairs = Object.keys(at).map(key=>{
            const value = at[key]
            const trans = transforms[key]
            const handler = trans && value && trans[value]
            return handler && [key, handler]
        }).filter(i=>i)
        const nAt = "content" in at ? {at, children: at.content, ...at} : {at, ...at}
        if(transPairs.length > 0) return createElement(SyncElement, {...nAt,transPairs,constr})
        //
        return createElement(constr, nAt)
    }
    return createNode
}

/******************************************************************************/

export const VDomAttributes = (sender) => {
    const onClick = ({"sendThen": ctx => event => { sender.send(ctx,patchFromValue("")) }}) //react gives some warning on stopPropagation
    const onChange = { "local": ctx => ch => true, "send": ctx => ch => false, "send_first": ctx => ch => ch }
    const ctx = { "ctx": ctx => ctx }
    const path = { "I": ctx => ctxToPath(ctx.identity) }
    const transforms = {onClick,onChange,ctx,path}
    return ({transforms})
}

export const VDomSender = () => ({
    send: (ctx, options) => ctx.branchContext.enqueue(ctx.identity,options)
})

export const Feedback = () => ({
    send: ({url,options}) => fetch(url,options)
})

export const CanvasManager = (canvasFactory) => {
    const useCanvas = UseCanvas({useBranch,useSync,canvasFactory})
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
const MessageReceiver = receivers => value => {
    const [k,v] = splitFirst("\n", value)
    receivers[k] && receivers[k](v)
}

export const mergeAll = l => {
	l.flatMap(h=>Object.keys(h)).toSorted().forEach((k,i,a) => k===a[i+1] && assertNever(k))
	return Object.assign({}, ...l)
}


const BranchContext = createContext(undefined)
BranchContext.displayName = "BranchContext"
export const useBranch = () => useContext(BranchContext) ?? assertNever("no BranchContext")
const createBranchProvider = ({
        createNode, sessionKey, branchKey, isRoot, win, login, enqueue, children,
}) => {
    const value = useMemo(()=>({
        createNode, sessionKey, branchKey, isRoot, win, login, enqueue
    }),[createNode, sessionKey, branchKey, isRoot, win, login, enqueue])
    return createElement(BranchContext.Provider, {value, children})
}
const useSync = UseSyncMod(useBranch)




export const RootComponents = ({createSyncProviders,checkActivate,receivers}) => {
    const locationComponents = LocationComponents({useBranch,useSync})
    const messageReceiver = MessageReceiver(receivers)
    const toAlienMessageComponents = ToAlienMessageComponents({messageReceiver,useBranch})
    const noReloadBranchKey = ()=>{}
    const noLogin = (u,p) => assertNever("no login in non-root")
    const Frame = ({branchKey,style}) => {
        const {createNode,sessionKey} = useBranch()
        const makeChildren = useCallback((body) => {
            const win = body.ownerDocument.defaultView ?? assertNever("no window")
            const syncProps = {
                createNode, login: noLogin, reloadBranchKey: noReloadBranchKey, isRoot: false, win, sessionKey, branchKey
            }
            return createElement(SyncRoot, syncProps)
        }, [createNode,sessionKey,branchKey])
        const props = useIsolatedFrame(makeChildren)
        return createElement("iframe", {...props, style})
    }
    const SyncRoot = (prop) => {
        const { enqueue, children, ack, busyFor, failure } = useSyncRoot(prop)
        const { createNode, sessionKey, branchKey, isRoot, win, login } = prop
        const sender = useMemo(()=>({ enqueue, ctxToPath, busyFor }), [enqueue, ctxToPath, busyFor])
        return createSyncProviders({sender,ack,isRoot,branchKey,children:
            createBranchProvider({createNode, sessionKey, branchKey, isRoot, win, login, enqueue, children: [
                failure && createElement('div', {key: 'failure'}, `VIEW FAILED: ${failure}`),
                createElement(AckContext.Provider, { key: 'ack-ctx', value: ack }, ...children)
            ]})
        })
    }
    const App = ({win,createNode}) => {
        useEffect(()=>manageAnimationFrame(win, checkActivate),[win, checkActivate])
        const {result,failure} = useSessionManager(win)
        return result ? createElement(SyncRoot, {createNode,...result}) : 
            failure ? `SESSION INIT FAILED: ${failure}` : ""
    }
    return ({...locationComponents,...toAlienMessageComponents,App,Frame})
}

export const activate = ({transforms, win, App}) => {
    const createNode = CreateNode({transforms})
    const [root, unmount] = doCreateRoot(win.document.body)
    root.render(createElement(App,{createNode,win}))
}
