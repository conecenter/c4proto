
import {weakCache,assertNever,getKey,asObject,asString,ObjS,EnqueuePatch,Patch,UnsubmittedPatch,resolve,ctxToPath,CreateNode} from "./util"

const asArray = (u: unknown): unknown[] => Array.isArray(u) ? u : assertNever("bad array")
const asBoolean = (u: unknown) => typeof u === "boolean" ? u : assertNever("bad boolean")

////

type OnClose = ()=>void 
type ConnectionParams = {
    win: Window, url: string, onData: (value: string)=>void, stateToSend: ()=>string, onClose: OnClose
}
const ReConnection = ({win,url,onData,stateToSend,onClose}:ConnectionParams) => {
    let wasAt = 0
    let wasFullAt = 0
    let ws: WebSocket|undefined
    const innerSend = (v: string) => ws && ws.readyState === ws.OPEN && ws.send(v)
    const innerClose = () => {
        try { ws && ws.readyState <= ws.OPEN && ws.close() } catch(e){ console.trace(e) }
    }
    const doSend = () => {
        innerSend(stateToSend())
        wasFullAt = Date.now()
    }
    const onMessage = (wsL: WebSocket, data: string) => {
        if(wsL !== ws) return
        data && onData(data)
        wasAt = Date.now()
    }
    const open = () => {
        const wsL = new win.WebSocket(url)
        wsL.addEventListener("open", ()=>doSend())
        wsL.addEventListener("message", ev=>onMessage(wsL,ev.data))
        wsL.addEventListener("close", ()=>onClose())
        ws = wsL
        wasAt = Date.now()
    }
    const periodic = win.setInterval(() => {
        Date.now() - wasFullAt > 30000 ? doSend() : innerSend("")
        Date.now() - wasAt > 5000 ? innerClose() : ws && ws.readyState > ws.OPEN && open()
    }, 1000)
    const close = () => {
        win.clearInterval(periodic)
        innerClose()
    }
    open()
    return {close, doSend}
}

////

type IncomingAt = ObjS<unknown> & {tp:string}
type Incoming = { at: IncomingAt, [x: `@${string}`]: unknown[], [x: `:${string}`]: Incoming }
const emptyIncoming: Incoming = {at:{tp:"span"}}
const isIncomingKey = (key: string): key is `:${string}` => key.startsWith(":")
const isChildOrderKey = (key: string): key is `@${string}` => key.startsWith("@")

const asObjectOrArray = (u: unknown): {}|unknown[] => typeof u === "object" && u ? u : assertNever("bad object")
const update = (inc: Incoming, spec: ObjS<unknown>, pKey: string, pIdentity: string): Incoming => {
    const res = {...inc}
    Object.keys(spec).forEach(key=>{
        //console.log("U",key,spec[key])
        const sValue: ObjS<unknown>|unknown[] = asObjectOrArray(spec[key])
        const value = Array.isArray(sValue) ? sValue : sValue["$set"] ?? sValue
        const cIdentity = pIdentity === "root" ? "" : resolve(pIdentity, key)
        if(isIncomingKey(key)) 
            res[key] = update((sValue !== value || !inc ? undefined : inc[key]) ?? emptyIncoming, asObject(value), key, cIdentity)
        else if(isChildOrderKey(key)) res[key] = asArray(value)
        else if(key === "at") res[key] = {tp: "span",...asObject(value), key: pKey, identity: cIdentity}
        else assertNever(`bad key in diff (${key})`)
    })
    return res
}

const getKeyOpt = (o: { [K: string]: unknown }, k: string): unknown => o[k]

type SetState<S> = (f: (was: S) => S) => void
type AckPatches = (index: number) => void
const Receiver = (setState: SetState<SyncRootState>, doAck: AckPatches, createNode: CreateNode) => {
    let incoming = {at:{tp:"RootElement"}}
    const elementWeakCache : (props:Incoming) => object = weakCache((cProps:Incoming)=>{
        let res = cProps.at
        Object.keys(cProps).forEach(key => {
            if(!isChildOrderKey(key)) return
            if(res === cProps.at) res = {...res}
            res[key.substring(1)] = (cProps[key] ?? assertNever("never")).map(kU => {
                const k = asString(kU)
                return elementWeakCache(isIncomingKey(k) && cProps[k] || assertNever("bad diff"))
            })
        })
        return createNode(res)
    })
    const receive = (data: string) => {
        const message = asObject(JSON.parse(data))
        const log = asArray(getKey(message,"log"))
        const availability = asBoolean(getKey(message,"availability"))
        const observerKey = asString(getKey(message,"observerKey"))
        log.forEach(d => {
            //console.log("I", res, d)
            incoming = update(
                {...emptyIncoming, ":root":incoming}, {":root": asObject(d)}, "root", "root"
            )[":root"] ?? assertNever("no incoming")
        })
        const rootElement = asObject(elementWeakCache(incoming))
        //
        const ackList = getKeyOpt(rootElement, "ackList")
        const ackEl = ackList && asArray(ackList).find(aU => getKey(asObject(aU), "observerKey") === observerKey)
        const ack = ackEl ? parseInt(asString(getKey(asObject(ackEl), "indexStr"))) : 0
        doAck(ack)
        //
        const failureU = getKeyOpt(rootElement,"failure")
        const failure = failureU ? asString(failureU) : ""
        const children = asArray(getKeyOpt(rootElement,"children") ?? [])
        setState(was => (
            log.length === 0 && was.availability === availability && was.ack === ack && was.failure === failure ? 
            was : ({ ...was, availability, children, ack, failure }) 
        ))
    }
    return {receive}
}

const serializeVals = (vs: Array<string|number>) => vs.map(v=>{
    //if(!{string:1,number:1}[typeof v]) console.log(vs)
    return `-${v}`.replaceAll("\n","\n ")
}).join("\n")
const serializePatch = (patch: Patch) => {
    const h: ObjS<string> = {...patch.headers, value: patch.value, "x-r-vdom-path": ctxToPath(patch.identity)}
    return serializeVals(Object.keys(h).toSorted().flatMap(k => [k, h[k] ?? assertNever("never")]))
}
const serializePatches = (patches: Patch[]) => serializeVals(
    patches.flatMap(patch => [patch.index, serializePatch(patch)])
)
const serializeState = (
    {isRoot,sessionKey,branchKey,patches}: {isRoot: boolean, sessionKey: string, branchKey: string, patches: Array<Patch>}
) => branchKey && sessionKey ? serializeVals(
    ["bs1", isRoot?"m":"s", branchKey, sessionKey, serializePatches(patches)]
) : ""

const PatchManager = () => {
    let nextPatchIndex = Date.now()
    let patches: Patch[] = []
    const enqueue: EnqueuePatch = patch => {
        const index = nextPatchIndex++
        const skip = (p: Patch) => p.skipByPath && p.identity === patch.identity
        patches = [...patches.filter(p=>!skip(p)), {...patch, index}]
        return index
    }
    const doAck: AckPatches = (index: number) => {
        patches = patches.filter(p=>p.index > index)
    }
    const getPatches = () => patches
    return {enqueue, doAck, getPatches}
}

const StartedSyncManager: (args: SyncManagerStartArgs)=>StartedSyncManager = ({
    createNode, setState, isRoot, sessionKey, branchKey, win, reloadBranchKey
}) => {
    const {enqueue, doAck, getPatches} = PatchManager()
    const {receive} = Receiver(setState, doAck, createNode)
    const stateToSend = () => serializeState({isRoot, sessionKey, branchKey, patches: getPatches()})
    const {close, doSend} = ReConnection({win, url: "/eventlog", onData: receive, stateToSend, onClose: reloadBranchKey})
    const send = (patch: UnsubmittedPatch) => {
        const index = enqueue(patch)
        doSend()
        return index
    }
    return {enqueue: send, stop: close}
}
const SyncManager = (): SyncManager => {
    let inner: StartedSyncManager | undefined
    const start = (args: SyncManagerStartArgs) => {
        if(inner) throw Error("started")
        inner = StartedSyncManager(args)
    }
    const getInner = () => inner ?? assertNever("not started")
    const enqueue: EnqueuePatch = patch => getInner().enqueue(patch)
    const stop: ()=>void = () => getInner().stop()
    return {start, enqueue, stop}
}

type SyncManagerStartArgs = {
    createNode: CreateNode, setState: SetState<SyncRootState>, 
    isRoot: boolean, sessionKey: string, branchKey: string, win: Window, reloadBranchKey: ()=>void
}
type StartedSyncManager = { enqueue: EnqueuePatch, stop: ()=>void }
type SyncManager = { start(args: SyncManagerStartArgs): void } & StartedSyncManager
export type SyncRootState = { 
    manager: SyncManager, availability: boolean, children: unknown[], ack: number, failure: string 
}
export const initSyncRootState = (): SyncRootState => ({ 
    manager: SyncManager(), availability: false, children: [], ack: 0, failure: "" 
})
