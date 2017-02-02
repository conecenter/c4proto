
import VDom          from "../main/vdom"
import VDomSender    from "../main/vdom-sender"
import VDomClicks    from "../main/vdom-clicks"
import VDomChanges   from "../main/vdom-changes"
import DiffPrepare   from "../main/diff-prepare"
import {mergeAll}    from "../main/util"

export default function VDomMix(feedback,transforms){
    const sender = VDomSender(feedback)
    const clicks = VDomClicks(sender)
    const changes = VDomChanges(sender, DiffPrepare)
    const seeds = VDomSeeds()
    const activeTransforms = mergeAll([transforms,clicks.transforms,changes.transforms,seeds.transforms])
    const vDom = VDom(document.body,activeTransforms)
    const branchHandlers = mergeAll(vDom.branchHandlers,changes.branchHandlers)
    return {branchHandlers}
}


