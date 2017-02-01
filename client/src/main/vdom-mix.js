
import VDom          from "../main/vdom"
import VDomSender    from "../main/vdom-sender"
import VDomClicks    from "../main/vdom-clicks"
import VDomChanges   from "../main/vdom-changes"
import DiffPrepare   from "../main/diff-prepare"

export default function VDomMix(feedback,transformsList){
    const sender = VDomSender(feedback)
    const clicks = VDomClicks(sender)
    const changes = VDomChanges(sender, DiffPrepare)
    const vDom = VDom(document.body,
        transformsList.concat(clicks.transforms).concat(changes.transforms)
    )
    const receiversList = [].concat(vDom.receivers).concat(changes.receivers)
    return {receiversList}
}
