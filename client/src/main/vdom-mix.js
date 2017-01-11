
import VDom          from "../main/vdom"
import VDomSender    from "../main/vdom-sender"
import Transforms    from "../main/vdom-transforms"
import InputChanges  from "../main/input-changes"
import DiffPrepare   from "../main/diff-prepare"
//import GridWatcher   from "../main/grid-watcher"
//import FieldPopup    from "../main/field-popup"

export default function VDomMix(feedback){
    const sender = VDomSender(feedback)
    const vDom = VDom(document.body)
    vDom.transformBy(InputChanges(sender, DiffPrepare))
    vDom.transformBy(Transforms(sender))
    //vDom.transformBy(GridWatcher(vDom, DiffPrepare))
    //vDom.transformBy(FieldPopup(vDom,DiffPrepare,sender))
    return vDom
}

/*
json wrap { value: diff, branchKey }
!InputChanges/changes by rCtx.branchKey
?VDomSender rCtx.branchKey
?bind/positioning
*/