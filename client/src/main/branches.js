
// functional?

import {mergeAll,branchesProp}    from "../main/util"

export default function Branches(log,branchHandlers){

    const toReceiver = branchHandler => data => {
        const i = data.indexOf(" ")
        const branchKey = data.substring(0,i)
        const body = data.substring(i+1)
        return branchHandler(branchKey,body)
    }

    const branches = data => branchesProp.modify(brs => {
        const active = data.split(";").map(res=>res.split(",")).map(res=>[res[0],res.slice(1)])
        //log({a:"active",active})
        return mergeAll(active.map( ([k,v]) => brs[k] ? {[k]:brs[k]} : {} ))
    })

    const receivers = mergeAll(
        Object.entries(branchHandlers)
            .map(([eventName,handler]) => ({[eventName]: toReceiver(handler)}))
            .concat({branches})
    )

//todo .branchKey; branch .checkActivate

    return ({receivers})
}
