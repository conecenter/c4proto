
import {spreadAll} from "../main/util.js"

export function rootCtx(ctx){ return ctx.parent ? rootCtx(ctx.parent) : ctx }

export function ctxToPath(ctx){
    return !ctx ? "" : ctxToPath(ctx.parent) + (ctx.key ? "/"+ctx.key : "")
}

export function VDomSender(feedback){ // todo: may be we need a queue to be sure server will receive messages in right order
    const send = (ctx, target) => {
        const rCtx = rootCtx(ctx)
        const headers = {
            ...target.headers,
            "x-r-branch": rCtx.branchKey,
            "x-r-vdom-path": ctxToPath(ctx)
        }
        const skipByPath = that => that.options.headers["x-r-vdom-path"] === headers["x-r-vdom-path"]
        return feedback.send({
            url: "/connection",
            options: { headers, body: target.value },
            defer: target.defer,
            skip: target.skipByPath && skipByPath,
            retry: target.retry //vdom-changes are more or less idempotent and can be retried
        },rCtx.modify)
    }
    return ({send})
}

export const pairOfInputAttributes = ({value,onChange,salt},headers) => {
    const values = (value+"\n").split("\n").slice(0,2)
    return values.map((value,index)=>({
        key: "input_"+index, value,
        onChange: ev => onChange({target:{
            headers,
            value: [...values.map((v,i)=>index===i?ev.target.value:values[i]), ...salt?[salt]:[]].join("\n")
        }})
    }))
};

export const chain = functions => arg => functions.reduce((res,f)=>f(res), arg)
export const deleted = ks => st => spreadAll(...Object.keys(st).filter(ck=>!ks[ck]).map(ck=>({[ck]:st[ck]})))

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

export const branchByKey = dictKeys(f=>({branchByKey:f}))

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

export const valueAt = key => prop => (key in prop) ? prop[key] : prop.at ? prop.at[key] : undefined
export const childrenAt = key => prop => map(o=>prop[o]||o)(prop[key])
export const identityOf = valueAt('identity')
export const identityAt = key => prop => ({ parent: identityOf(prop), key })
export const never = o => { console.log(o); throw new Error }
export const map = f => l => l && l.map && l.map(f) || l && never(l)
export const head = l => l && l[0]