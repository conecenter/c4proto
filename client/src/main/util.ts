
export const spreadAll = <T>(args: T[]): T => Object.assign({},...args)

type ObservableElement = {
    addEventListener: <E>(evName: string, callback: (ev: E) => void) => void
    removeEventListener: <E>(evName: string, callback: (ev: E) => void) => void
}
export const manageEventListener = <E>(el: ObservableElement, evName: string, callback: (ev: E) => void) => { //EventTarget,Event
    el.addEventListener(evName,callback)
    //console.log(`on ${evName}`)
    return ()=>{
        el.removeEventListener(evName,callback)
        //console.log(`off ${evName}`)
    }
}

export const weakCache = <K extends WeakKey,V>(f: (key: K)=>V): (key: K)=>V => {
    const map = new WeakMap
    return (arg:K) => {
        if(map.has(arg)) return map.get(arg)
        const res = f(arg)
        map.set(arg,res)
        return res
    }
}

export type SetState<S> = (f: (was: S) => S) => void

export const assertNever = (m: string) => { throw new Error(m) }

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
