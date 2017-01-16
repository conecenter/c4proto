
import VDom          from "../main/vdom"
import VDomSender    from "../main/vdom-sender"
import VDomClicks    from "../main/vdom-clicks"
import InputChanges  from "../main/input-changes"
import DiffPrepare   from "../main/diff-prepare"

export default function VDomMix(feedback,transforms){
    const sender = VDomSender(feedback)
    const clicks = VDomClicks(sender)
    const changes = InputChanges(sender, DiffPrepare)
    const vDom = VDom(document.body,
        transforms.concat(clicks.transforms).concat(changes.transforms)
    )
    const receivers = vDom.receivers.concat(changes.receivers)
    return {receivers}
}
