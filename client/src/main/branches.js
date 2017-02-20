
import {mergeAll}    from "../main/util"

export default function Branches(log,branchHandlers){

    const updateBranch = (branchKey,values) => state => ({
        ...state, branches: {...state.branches, [branchKey]: {...state.branches[branchKey], ...values} }
    })

    const init = state => state.updateBranch ? state : {...state, updateBranch}

    const toReceiver = branchHandler => data => state => {
        const i = data.indexOf(" ")
        const branchKey = data.substring(0,i)
        const body = data.substring(i+1)
        return branchHandler(branchKey,body)(init(state))
    }

    const branches = data => state => {
        const active = data.split(";").map(res=>res.split(",")).map(res=>[res[0],res.slice(1)])
        //log({a:"active",active})
        const branches = mergeAll(active.map(
            ([k,v]) => state.branches[k] ? {[k]:state.branches[k]} : {}
        ))
        return {...state, branches}
    }

    const receivers = mergeAll(
        Object.entries(branchHandlers)
            .map(([eventName,handler]) => ({[eventName]: toReceiver(handler)}))
            .concat({branches})
    )

    const checkActivate = state => chain(
        Object.values(state.branches||{}).map(b=>b.checkActivate).filter(v=>v)
    )(init(state))


// .branchKey
// branch .checkActivate

    return ({receivers,checkActivate})
}
