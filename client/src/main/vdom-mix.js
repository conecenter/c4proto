
import VDom          from "../main/vdom"
import VDomClicks    from "../main/vdom-clicks"
import VDomChanges   from "../main/vdom-changes"
import {VDomSeeds,rootCtx,ctxToPath} from "../main/vdom-util"
import {mergeAll,transformNested}    from "../main/util"

export default function VDomMix({log,encode,transforms,getRootElement,createElement}){
    const clicks = VDomClicks(rootCtx)
    const changes = VDomChanges(rootCtx,transformNested)
    const seeds = VDomSeeds(log,transformNested)
    const activeTransforms = mergeAll([transforms,clicks.transforms,changes.transforms,seeds.transforms])
    const vDom = VDom({getRootElement, createElement, activeTransforms, encode, rootCtx, ctxToPath, mergeAll})
    const branchHandlers = mergeAll([vDom.branchHandlers,changes.branchHandlers])
    return ({branchHandlers})
}


