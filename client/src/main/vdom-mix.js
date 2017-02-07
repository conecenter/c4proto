
import VDom          from "../main/vdom"
import VDomClicks    from "../main/vdom-clicks"
import VDomChanges   from "../main/vdom-changes"
import {VDomSeeds}   from "../main/vdom-util"
import DiffPrepare   from "../main/diff-prepare"
import {mergeAll}    from "../main/util"

export default function VDomMix(log,sender,transforms,getRootElement,createElement){
    const clicks = VDomClicks(sender)
    const changes = VDomChanges(sender, DiffPrepare)
    const seeds = VDomSeeds(log)
    const activeTransforms = mergeAll([transforms,clicks.transforms,changes.transforms,seeds.transforms])
    const vDom = VDom(getRootElement,createElement,activeTransforms)
    const branchHandlers = mergeAll([vDom.branchHandlers,changes.branchHandlers])
    return ({branchHandlers})
}


