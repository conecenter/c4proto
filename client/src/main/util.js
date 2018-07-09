
function isObj(a){ return a.constructor===Object }

export function mergeAll(list){
    return list.reduce(merger((left,right) => { throw ["unable to merge",left,right] }), {})
}

export function merger(resolve){
    const mergePair = (left,right) => (
        isObj(left) && isObj(right) ?
        Object.keys(right).filter(k => k in left).reduce(
            (was,k) => ({...was, [k]: mergePair(left[k],right[k]) }),
            {...left,...right}
        ) :
        resolve(left,right)
    )
    return mergePair
}

export function splitFirst(splitter,data){
    const i = data.indexOf(splitter)
    return [data.substring(0,i), data.substring(i+1)]
}

