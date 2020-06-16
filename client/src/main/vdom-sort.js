
import {useCallback,useMemo,createElement} from 'react'
import {SortableContainer,SortableElement,SortableHandle} from 'react-sortable-hoc'
import {useSync} from './vdom-core'

const SortHandle = SortableHandle(({children}) => children)

const SortElement = SortableElement(({children}) => children)

const SortContainer = SortableContainer(({children,tp,...props}) => {
    return createElement(tp, props,
        children.map((child,i)=>createElement(SortElement,{index:i,key:child.key},child))
    )
})

const applyPatches = (value,patches) => {
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

const useSortRoot = (identity,value) => {
    const [patches,enqueuePatch] = useSync(identity)
    const patchedValue = useMemo(()=>applyPatches(value,patches),[value,patches])
    const onSortEnd = useCallback(({oldIndex, newIndex}) => {
        createPatchOpt(patchedValue, oldIndex, newIndex).forEach(p=>enqueuePatch(p))
    },[patchedValue])
    return [patchedValue,onSortEnd]
}

const sortChildren = (patchedValue,children) => {
    const childrenByKey = Object.fromEntries(children.map(c=>[c.key,c]))
    return patchedValue.map(k=>childrenByKey[`:${k}`])
}

function TBodySortRoot({identity,value,children,...props}){
    const [patchedValue,onSortEnd] = useSortRoot(identity,value)
    const sortedChildren = sortChildren(patchedValue,children||[])
    return createElement(SortContainer,{tp:"tbody",useDragHandle:true,onSortEnd,...props},sortedChildren)
}

export const sortTransforms = ({tp:{TBodySortRoot,SortHandle}})
