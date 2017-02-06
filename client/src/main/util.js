
export function mergeAll(list){
    function merge(to,from){
        Object.keys(from).forEach(key=>{
            if(!to[key]) to[key] = from[key]
            else if(to[key].constructor===Object && from[key].constructor===Object)
                merge(to[key],from[key])
            else throw ["unable to merge",to[key],from[key]]
        })
    }
    const to = {}
    list.forEach(from=>merge(from,to))
    return to
}