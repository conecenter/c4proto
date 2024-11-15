
function isObj(a){ return a.constructor===Object }

export function mergeAll(list){
    return list.reduce(merger((left,right) => { throw ["unable to merge",left,right] }), {})
}

export function spreadAll(...args){ return Object.assign({},...args) }

function merger(resolve){
    const mergePair = (left,right) => (
        isObj(left) && isObj(right) ?
        spreadAll(left,right,...Object.keys(right).filter(k => k in left).map(
            k => ({[k]: mergePair(left[k],right[k]) })
        )) :
        resolve(left,right)
    )
    return mergePair
}

export const manageEventListener = (el, evName, callback) => {
    if(!callback || !el) return undefined
    el.addEventListener(evName,callback)
    //console.log(`on ${evName}`)
    return ()=>{
        el.removeEventListener(evName,callback)
        //console.log(`off ${evName}`)
    }
}

export const weakCache = f => {
    const map = new WeakMap
    return arg => {
        if(map.has(arg)) return map.get(arg)
        const res = f(arg)
        map.set(arg,res)
        return res
    }
}

export const identityAt = key => weakCache(parent => ({ parent, key }))
