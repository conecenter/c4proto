
// functional?

import {mergeAll,branchesByKeyProp,branchesActiveProp}    from "../main/util"

export default function Branches(log,branchHandlers){

    const toReceiver = branchHandler => data => {
        const i = data.indexOf(" ")
        const branchKey = data.substring(0,i)
        const body = data.substring(i+1)
        return branchHandler(branchKey,body)
    }

    const branches = data => state => {
        const active = data.split(";").map(res=>res.split(",")[0]) //.map(res=>res.split(",")).map(res=>[res[0],res.slice(1)])
        const clear = brs => mergeAll(active.map( k => brs[k] ? {[k]:brs[k]} : {} ))
        return chain([branchesByKeyProp.modify(clear),branchesActiveProp.set(active)])
    }


    const receivers = mergeAll(
        Object.entries(branchHandlers)
            .map(([eventName,handler]) => ({[eventName]: toReceiver(handler)}))
            .concat({branches})
    )

//todo .branchKey; branch .checkActivate

    return ({receivers})
}
