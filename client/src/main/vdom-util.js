
import {spreadAll} from "../main/util.js"

export function rootCtx(ctx){ return ctx.parent ? rootCtx(ctx.parent) : ctx }





export const chain = functions => arg => functions.reduce((res,f)=>f(res), arg)
const deleted = ks => st => spreadAll(...Object.keys(st).filter(ck=>!ks[ck]).map(ck=>({[ck]:st[ck]})))

const oneKey = (k,by) => st => {
    const was = st && st[k]
    const will = by(was)
    return was === will ? st : will ? {...(st||{}), [k]: will} : !st ? st : deleted({[k]:1})(st)

}
export const someKeys = bys => chain(Object.keys(bys).map(k=>oneKey(k,bys[k])))
const allKeys = by => state => state ? chain(Object.keys(state).map(k=>oneKey(k,by)))(state) : state
export const dictKeys = f => ({
    one: (k,by) => someKeys(f(oneKey(k,by))),
    all: by => someKeys(f(allKeys(by)))
})

export const ifInputsChanged = log => (cacheKey,inpKeysObj,f) => {
    const inpKeys = Object.keys(inpKeysObj)
    const changed = state => {
        const will = spreadAll(...inpKeys.map(k=>({[k]: state && state[k]})))
        return ({...state, [cacheKey]:will})
    }
    const doRun = f(changed)
    return state => {
        const was = state && state[cacheKey]
        if(inpKeys.every(k=>(was && was[k])===(state && state[k]))) return state
        const res = doRun(state)
        log({hint:cacheKey, status:state===res?"deferred":"done", state:res})
        return res
    }
}
