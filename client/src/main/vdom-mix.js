
import VDom          from "../main/vdom"
import VDomClicks    from "../main/vdom-clicks"
import VDomChanges   from "../main/vdom-changes"
import {VDomSeeds,rootCtx,ctxToPath} from "../main/vdom-util"
import DiffPrepare   from "../main/diff-prepare"
import {mergeAll}    from "../main/util"

export default function VDomMix({log,encode,transforms,getRootElement,createElement}){
    const clicks = VDomClicks(rootCtx)
    const changes = VDomChanges(rootCtx, DiffPrepare)
    const seeds = VDomSeeds(log)
    const activeTransforms = mergeAll([transforms,clicks.transforms,changes.transforms,seeds.transforms])
    const vDom = VDom({getRootElement, createElement, activeTransforms, encode, rootCtx, ctxToPath})
    const branchHandlers = mergeAll([vDom.branchHandlers,changes.branchHandlers])
    return ({branchHandlers})
}


