
export const manageEventListener = <K extends keyof EventMap>(
    el: EventTarget, evName: K, callback: (ev: EventMap[K]) => void
) => {
    el.addEventListener(evName,callback)
    //console.log(`on ${evName}`)
    return ()=>{
        el.removeEventListener(evName,callback)
        //console.log(`off ${evName}`)
    }
}

export const weakCache = <K extends object,V>(f: (key: K)=>V): (key: K)=>V => {
    const map = new WeakMap<K,V>
    return (arg:K) => {
        const cachedRes = map.get(arg)
        if(cachedRes !== undefined) return cachedRes
        const res = f(arg)
        map.set(arg,res)
        return res
    }
}

export const assertNever = (m: string) => { throw Error(m) }

export const manageAnimationFrame = (element: HTMLElement, callback: ()=>void) => {
    const win = element.ownerDocument.defaultView
    if(!win) return
    const {requestAnimationFrame,cancelAnimationFrame} = win
    const animate = () => {
        callback()
        req = requestAnimationFrame(animate)
    }
    let req = requestAnimationFrame(animate)
    return () => cancelAnimationFrame(req)
}

export const getKey = (o: { [K: string]: unknown }, k: string): unknown => k in o ? o[k] : assertNever(`no key (${k})`)
export const asObject = (u: unknown): {} => typeof u === "object" && u !== null && !Array.isArray(u) ? u : assertNever("bad object")
export const asString = (u: unknown) => typeof u === "string" ? u : assertNever("bad string")

export type ObjS<T> = { [x: string]: T }
export type SetState<S> = (f: (was: S) => S) => void
export type Identity = string // identity is string, it should not change on patch, it's in many hook deps
export type UnsubmittedPatch = { identity: Identity, skipByPath: boolean, value: string, headers?: ObjS<string> }
export type Patch = UnsubmittedPatch & { index: number }
export type EnqueuePatch = (patch: UnsubmittedPatch) => number
export type CreateNode = (at: ObjS<unknown> & {tp:string}, childAt: ObjS<unknown[]>)=>object
export type BranchContext = { branchKey: string, sessionKey: string, enqueue: EnqueuePatch, isRoot: boolean, win:Window }

export const resolve = (identity: Identity, key: string) => identity+'/'+key
export const identityAt = (key: string): (identity: Identity)=>Identity => identity => resolve(identity, key)
export const ctxToPath = (ctx: Identity): string => ctx
