
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
