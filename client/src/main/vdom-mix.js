
import VDom          from "../main/vdom"
import VDomClicks    from "../main/vdom-clicks"
import VDomChanges   from "../main/vdom-changes"
import VDomSeeds     from "../main/vdom-seeds"
import {chain,mergeAll}    from "../main/util"

export default function VDomMix({log,encode,transforms,getRootElement,createElement}){
    const clicks = VDomClicks()
    const changes = VDomChanges()
    const seeds = VDomSeeds(log)
    const activeTransforms = mergeAll([transforms,clicks.transforms,changes.transforms,seeds.transforms])
    const vDom = VDom({getRootElement, createElement, activeTransforms, encode})
    const branchHandlers = mergeAll([vDom.branchHandlers,changes.branchHandlers])
    const checkActivate = chain([seeds.checkActivate,vDom.checkActivate])
    return ({branchHandlers,checkActivate})
}


