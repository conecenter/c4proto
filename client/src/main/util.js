
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
    list.forEach(from=>merge(to,from))
    return to
}

export const chain = args => state => args.reduce((st,f) => f(st), state)

////

/*
export const transformNested = (name,inner) => state => {
    const was = state[name]
    const will = inner(was)
    return was===will ? state : {...state, [name]: will}
}
*/

const modify = lens => transform => state => lens.set(transform(lens.of(state)))(state)
const richLens = lens => ({ ...lens, modify: modify(lens), compose: compose(lens) })
const compose = oLens => iLens => richLens({
    of: state => iLens.of(oLens.of(state)),
    set: value => modify(oLens)(iLens.set(value))
})
export const lensProp = key => richLens({
    of: state => state && state[key],
    set: value => state => state[key]===value ? state : ({...state, [key]:value})
})

////

export const branchesProp = lensProp("branches")
export const branchProp = branchKey => branchesProp.compose(lensProp(branchKey))
export const connectionProp = lensProp("connection")

export const branchSend = options => connectionProp.modify(addSend({url:"/connection", options}))

export const addSend = message => was => {
    const lastMessageIndex = ((was||{}).lastMessageIndex||0) + 1
    const toSend = [...((was||{}).toSend || []),sessionKey=>{
        const headers = {...message.options.headers, "X-r-index": lastMessageIndex, "X-r-session": sessionKey}
        const options = { method:"post", ...message.options, headers}
        return ({...message, options})
    }]
    return ({...was, lastMessageIndex, toSend})
}

