
import {weakCache,SetState,assertNever,getKey,asObject,asString,ObjS,EnqueuePatch,Patch,UnsubmittedPatch,resolve,ctxToPath,CreateNode} from "./util"

const asArray = (u: unknown): unknown[] => Array.isArray(u) ? u : assertNever("bad array")
const asBoolean = (u: unknown) => typeof u === "boolean" ? u : assertNever("bad boolean")

////

type ConnectionParams = {win: Window, url: string, onData: (value: string)=>void, stateToSend: ()=>string}
const ReConnection = (args: ConnectionParams & { onIdle: ()=>void }) => {
    const {win, onIdle} = args
    let connection = Connection(args)
    const hShort = win.setInterval(() => {
        connection.ping()
        if(connection.expired()){
            connection.close()
            onIdle()
            connection = Connection(args)
        }
    }, 1000)
    const hLong = win.setInterval(()=>connection.doSend(), 30000)
    const close = () => {
        win.clearInterval(hShort)
        win.clearInterval(hLong)
        connection.close()
    }
    const doSend = ()=>connection.doSend()
    return {close, doSend}
}
const Connection = ({win,url,onData,stateToSend}:ConnectionParams) => {
    let ready = false
    let wasAt = Date.now()
    const ping = () => ready && ws.send("")
    const doSend = () => ready && ws.send(stateToSend())
    const expired = () => Date.now() - wasAt > 5000
    const onMessage = (ev: MessageEvent) => {
        if(ev.data && onData) onData(ev.data)
        wasAt = Date.now()
    }
    const onOpen = () => {
        ready = true
        doSend()
    }
    const ws = new win.WebSocket(url)
    ws.addEventListener("open", onOpen)
    ws.addEventListener("message", onMessage)
    const close = () => {
        ws.removeEventListener("message", onMessage)
        ws.removeEventListener("open", onOpen)
        try { ws.close() } catch(e){ console.trace(e) }
    }
    return {close, doSend, ping, expired}
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
        else if(key === "at") res[key] = {...emptyIncoming.at,...asObject(value), key: pKey, identity: cIdentity}
        else assertNever(`bad key in diff (${key})`)
    })
    return res
}

type AckPatches = (index: number) => void
const Receiver = (setState: SetState<SyncRootState>, doAck: AckPatches, createNode: CreateNode) => {
    let incoming = emptyIncoming
    const elementWeakCache : (props:Incoming) => object = weakCache(({at,...cProps}:Incoming)=>{
        const childAt: ObjS<unknown[]> = Object.fromEntries(Object.keys(cProps).map(key => {
            if(!isChildOrderKey(key)) return undefined
            const children = (cProps[key] ?? assertNever("never")).map(kU => {
                const k = asString(kU)
                return elementWeakCache(isIncomingKey(k) && cProps[k] || assertNever("bad diff"))
            })
            return [key.substring(1), children]
        }).filter(it => it !== undefined))
        return createNode(at,childAt)
    })
    const receive = (data: string) => {
        const message = asObject(JSON.parse(data))
        const log = asArray(getKey(message,"log"))
        const availability = asBoolean(getKey(message,"availability"))
        const observerKey = asString(getKey(message,"observerKey"))
        log.forEach(d => {
            //console.log("I", res, d)
            const setCh = "@children" in incoming ? {} : {"@children":{"$set":[":root"]}}
            incoming = update(incoming, {":root":d,...setCh}, "root", "root")
        })
        const rootElement = asObject(elementWeakCache(incoming))
        //
        const ackList = getKey(rootElement,"ackList")
        const ackEl = ackList && asArray(ackList).find(aU => getKey(asObject(aU), "observerKey") === observerKey)
        const ack = ackEl ? parseInt(asString(getKey(asObject(ackEl), "indexStr"))) : 0
        doAck(ack)
        //
        const failureU = getKey(rootElement,"failure")
        const failure = failureU ? asString(failureU) : ""
        const children = getKey(rootElement,"children")
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
    const {close, doSend} = ReConnection({win, url: "/eventlog", onData: receive, stateToSend, onIdle: reloadBranchKey})
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
    manager: SyncManager, availability: boolean, children?: unknown, ack: number, failure: string 
}
export const initSyncRootState = (): SyncRootState => ({ manager: SyncManager(), availability: false, ack: 0, failure: "" })
