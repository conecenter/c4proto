
import {useCallback,useMemo,createElement,Children} from "../main/react-prod.js"
import {SortableContainer,SortableElement,SortableHandle} from "../main/react-sortable-hoc-prod.js"
import {useSync, traverseOne} from "../main/vdom-core.js"

import { valueAt, childrenAt, identityAt, resolve } from "../main/vdom-util.js"

const childrenOf = valueAt('children')
const sortIdOf = identityAt('sort')

const SortHandle = SortableHandle(prop => Children.map(childrenOf(prop).map(resolve(prop)), traverseOne))

const SortElement = SortableElement(({children}) => children)

export const SortContainer = SortableContainer(({children,tp,...prop}) => (
    createElement(tp, {...prop}, children.map((child,index)=>(
        createElement(SortElement,{index,key:child.key},child)
    )))
))

const applyPatches = patches => value => { //memo?
    return patches.reduce((acc,{headers})=>acc.flatMap(key => {
        const obj = headers["x-r-sort-obj-key"]
        const order = [headers["x-r-sort-order-0"],headers["x-r-sort-order-1"]]
        return key===obj ? [] : order.includes(key) ? order : [key]
    }),value)
}

const createPatchOpt = (patchedValue, oldIndex, newIndex) => {
    if(oldIndex === newIndex) return []
    const obj = patchedValue[oldIndex]
    const to = patchedValue[newIndex]
    const order = oldIndex < newIndex ? [to,obj] : [obj,to]
    const headers = {
        "x-r-sort-obj-key": obj,
        "x-r-sort-order-0": order[0],
        "x-r-sort-order-1": order[1],
    }
    return [{headers,retry:true}]
}

export const useSortRoot = identity => {
    const [patches,enqueuePatch] = useSync(identity)
    const container = ({children,...prop}) => {
        const onSortEnd = ({oldIndex, newIndex}) => {
            createPatchOpt(children.map(c=>c.key), oldIndex, newIndex).forEach(enqueuePatch)
        }
        return createElement(SortContainer,{children,...prop,onSortEnd})
    }
    return [applyPatches(patches),container]
}

function TBodySortRoot(prop){
    const [applyPatches,container] = useSortRoot(sortIdOf(prop))
    const children = applyPatches(childrenOf(prop)||[]).map(resolve(prop)).map(traverseOne)
    return container({tp:"tbody",useDragHandle:true,children})
}

export const sortTransforms = ({tp:{TBodySortRoot,SortHandle}})
