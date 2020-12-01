

import { createElement as $, useMemo, useState, useLayoutEffect, cloneElement, useCallback, useEffect } from "react"

import { map, identityAt, deleted, never } from "./vdom-util.js"
import { useWidth, useEventListener, useSync, NoCaptionContext } from "./vdom-hooks.js"

const dragRowIdOf = identityAt('dragRow')
const dragColIdOf = identityAt('dragCol')

const ROW_KEYS = {
    HEAD: "head",
    DRAG: "drag",
}

const GRID_CLASS_NAMES = {
    CELL: "tableCellContainer headerColor-border",
}

//// col hiding

const sortedWith = f => l => l && [...l].sort(f)

//

const partitionVisibleCols = (cols, outerWidth) => {
    const fit = (count, accWidth) => {
        const col = cols[count]
        if (!col) return count
        const willWidth = accWidth + col.minWidth
        if (outerWidth < willWidth) return count
        return fit(count + 1, willWidth)
    }
    const count = fit(0, 0)
    return [cols.slice(0, count), cols.slice(count)]
}

const sortedByHideWill = sortedWith((a, b) => a.hideWill - b.hideWill)

const calcHiddenCols = (cols, outerWidth) => {
    const [visibleCols, hiddenCols] = partitionVisibleCols(sortedByHideWill(cols), outerWidth)
    const hasHiddenCols = hiddenCols.length > 0
    const hiddenColSet = hasHiddenCols && new Set(colKeysOf(hiddenCols))
    const hideElementsForHiddenCols = (mode,toColKey) => (
        hasHiddenCols ? (children => children.filter(c => mode === hiddenColSet.has(toColKey(c)))) :
            mode ? (children => []) : (children => children)
    )
    return { hasHiddenCols, hideElementsForHiddenCols }
}

//// expanding
const useExpanded = () => {
    const [expanded, setExpanded] = useState({})
    const setExpandedItem = useCallback((key, f) => setExpanded(was => {
        const wasValue = !!was[key]
        const willValue = !!f(wasValue)
        return wasValue === willValue ? was : willValue ? { ...was, [key]: 1 } : deleted({ [key]: 1 })(was)
    }), [setExpanded])
    return [expanded, setExpandedItem]
}
const useExpandedElements = (expanded, setExpandedItem) => {
    const toExpanderElements = useCallback((on,cols,children) => on ? children.map(c => {
        const { isExpander, rowKey } = c.props
        return isExpander && rowKey ? cloneElement(c, {
            onClick: ev => setExpandedItem(rowKey, v => !v),
            expander: expanded[rowKey] ? 'expanded' : 'collapsed',
        }) : c
    }) : colKeysOf(cols.filter(col=>col.isExpander))
        .reduce((resCells,expanderColKey)=>resCells.filter(cell=>cell.props.colKey!==expanderColKey), children)
    , [expanded, setExpandedItem])
    const getExpandedCells = useCallback(({ cols, rowKeys, children }) => {
        if (cols.length <= 0) return []
        const posStr = (rowKey, colKey) => rowKey + colKey
        const expandedByPos = Object.fromEntries(
            children.filter(c => expanded[c.props.rowKey])
                .map(c => [posStr(c.props.rowKey, c.props.colKey), c])
        )
        return rowKeys.filter(rowKey => expanded[rowKey]).map(rowKey => {
            const pairs = cols.map(col => {
                const cell = expandedByPos[posStr(rowKey, col.colKey)]
                return [col, cell]
            })
            return [rowKey, pairs]
        })
    }, [expanded])
    return { toExpanderElements, setExpandedItem, getExpandedCells }
}

const expandRowKeys = expanded => rowKeys => rowKeys.flatMap(rowKey => (
    expanded[rowKey] ? [{ rowKey }, { rowKey, rowKeyMod: "-expanded" }] : [{ rowKey }]
))

const hideExpander = hasHiddenCols => hasHiddenCols ? (l => l) : (l => l.filter(c => !c.isExpander))

//// drag model
/*
const patchEqParts = [
    p=>p.headers["x-r-sort-obj-key"],
    p=>p.headers["x-r-sort-order-0"],
    p=>p.headers["x-r-sort-order-1"],
]
const patchEq = (a,b) => patchEqParts.every(f=>f(a)===f(b))
*/
const applyPatches = patches => value => { //memo?
    return patches.reduce((acc, { headers }) => {
        const obj = headers["x-r-sort-obj-key"]
        const order = [headers["x-r-sort-order-0"], headers["x-r-sort-order-1"]]
        return acc.flatMap(key => key === obj ? [] : order.includes(key) ? order : [key])
    }, value)
}
/*
const createPatch = (keys,from,to) => {
    const fromIndex = keys.indexOf(from)
    const toIndex = keys.indexOf(to)
    if(fromIndex < 0 || toIndex < 0 || fromIndex === toIndex) return null
    const order = fromIndex < toIndex ? [to,from] : [from,to]
    const headers = {
        "x-r-sort-obj-key": from,
        "x-r-sort-order-0": order[0],
        "x-r-sort-order-1": order[1],
    }
    return {headers,retry:true}
}*/
const createPatch = (keys, from, to, d) => {
    const order = d > 0 ? [to, from] : d < 0 ? [from, to] : null
    if (!keys.includes(from) || !keys.includes(to) || !order || from === to) return null
    const headers = {
        "x-r-sort-obj-key": from,
        "x-r-sort-order-0": order[0],
        "x-r-sort-order-1": order[1],
    }
    return { headers, retry: true }
}

const useSortRoot = (identity, keys, transientPatch) => {
    const [patches, enqueuePatch] = useSync(identity)
    const patchedKeys = useMemo(() => applyPatches(transientPatch ? [...patches, transientPatch] : patches)(keys), [patches, keys, transientPatch])
    return [patchedKeys, enqueuePatch]
}

const remapCols = cols => {
    const colByKey = Object.fromEntries(cols.map(c => [c.colKey, c]))
    return colKeys => colKeys.map(k => colByKey[k])
}

//// main

const getGridRow = ({ rowKey, rowKeyMod }) => CSS.escape(rowKey + (rowKeyMod || ''))
const getGridCol = ({ colKey }) => CSS.escape(colKey)

const spanAll = "1 / -1"

export function GridCell({ identity, children, rowKey, rowKeyMod, colKey, isExpander, expander, dragHandle, noDefCellClass, className: argClassName, gridRow: argGridRow, gridColumn: argGridColumn, ...props }) {
    const gridRow = argGridRow || getGridRow({ rowKey, rowKeyMod })
    const gridColumn = argGridColumn || getGridCol({ colKey })
    const style = { ...props.style, gridRow, gridColumn }
    const expanderProps = isExpander ? { 'data-expander': expander || 'passive' } : {}
    const className = noDefCellClass ? argClassName : `${argClassName} ${GRID_CLASS_NAMES.CELL}`
    return $("div", { ...props, ...expanderProps, 'data-col-key': colKey, 'data-row-key': rowKey, "data-drag-handle": dragHandle, style, className }, children)
}

const colKeysOf = children => children.map(c => c.colKey)

const getGidTemplateRows = rows => rows.map(o => `[${getGridRow(o)}] auto`).join(" ")
const getGridTemplateColumns = columns => columns.map(col => {
    const key = getGridCol(col)
    const width = `minmax(${col.minWidth}em,${col.maxWidth}em)`
    return `[${key}] ${width}`
}).join(" ")

const noChildren = []
export function GridRoot({ identity, rowKeys, cols, children: rawChildren }) {
    const children = rawChildren || noChildren//Children.toArray(rawChildren)
    const [dragData, setDragData] = useState({})
    const { axis, patch: dropPatch } = dragData

    const [patchedRowKeys, enqueueRowPatch] = useSortRoot(dragRowIdOf(identity), rowKeys, axis ? switchAxis(null, dropPatch)(axis) : null)
    const colKeys = useMemo(() => colKeysOf(cols), [cols])
    const [patchedColKeys, enqueueColPatch] = useSortRoot(dragColIdOf(identity), colKeys, axis ? switchAxis(dropPatch, null)(axis) : null)
    const patchedCols = useMemo(() => remapCols(cols)(patchedColKeys), [cols, patchedColKeys])

    const [gridElement, setGridElement] = useState(null)

    const [rootDragStyle, onMouseDown, draggingStart] = useGridDrag({
        dragData, setDragData, gridElement,
        ...(axis ? switchAxis(
            { keys: colKeys, enqueuePatch: enqueueColPatch },
            { keys: rowKeys, enqueuePatch: enqueueRowPatch },
        )(axis) : {})
    })

    const [expanded, setExpandedItem] = useExpanded()

    const hasDragRow = useMemo(()=>children.some(c=>c.props.dragHandle==="x"),[children])
    const gridTemplateRows = useMemo(() => getGidTemplateRows([
        ...(hasDragRow ? [{ rowKey: ROW_KEYS.DRAG }]:[]),
        { rowKey: ROW_KEYS.HEAD },
        ...expandRowKeys(expanded)(patchedRowKeys)
    ]), [hasDragRow, expanded, patchedRowKeys])

    const outerWidth = useWidth(gridElement)
    const { hasHiddenCols, hideElementsForHiddenCols } =
        useMemo(() => calcHiddenCols(cols, outerWidth), [cols, outerWidth])
    const gridTemplateColumns = useMemo(() => getGridTemplateColumns(
        hideExpander(hasHiddenCols)(hideElementsForHiddenCols(false,col=>col.colKey)(patchedCols))
    ), [patchedCols, hideElementsForHiddenCols, hasHiddenCols])

    const { toExpanderElements, getExpandedCells } = useExpandedElements(expanded, setExpandedItem)

    const allChildren = useMemo(()=>getAllChildren({
        children,rowKeys,cols,draggingStart,hasHiddenCols,hideElementsForHiddenCols,toExpanderElements,getExpandedCells
    }),[children,rowKeys,cols,draggingStart,hasHiddenCols,hideElementsForHiddenCols,toExpanderElements,getExpandedCells])

    useEffect(() => {
        const { dragKey, axis } = draggingStart
        if (axis === "y") setExpandedItem(dragKey, v => false)
    }, [setExpandedItem, draggingStart])

    const style = { ...rootDragStyle, display: "grid", gridTemplateRows, gridTemplateColumns }
    const res = $("div", { onMouseDown, style, className: "grid", ref: setGridElement }, allChildren)
    return $(NoCaptionContext.Provider,{value:true},res)
}

const getAllChildren = ({children,rowKeys,cols,draggingStart,hasHiddenCols,hideElementsForHiddenCols,toExpanderElements,getExpandedCells}) => {
    const dropElements = getDropElements(draggingStart)

    const expandedElements = getExpandedCells({
        rowKeys, children, cols: hideElementsForHiddenCols(true,col=>col.colKey)(cols),
    }).map(([rowKey, pairs]) => {
        const res = $(GridCell, {
            key: `${rowKey}-expanded`,
            gridColumn: spanAll,
            rowKey,
            rowKeyMod: "-expanded",
            style: { display: "flex", flexFlow: "row wrap" },
            children: pairs.map(([col, cell]) => $("div",{
                key: cell.key,
                style: { flexBasis: `${col.minWidth}em` },
                className: "inputLike",
                children: cell.props.children,
            }))
        })
        return $(NoCaptionContext.Provider,{value:false},res)
    })
    const allChildren = toExpanderElements(hasHiddenCols,cols,[...dropElements, ...toDraggingElements(draggingStart)(hideElementsForHiddenCols(false,cell=>cell.props.colKey)([
        ...children, ...expandedElements
    ]))])
    console.log("inner render")
    return allChildren
}

/*,(a,b)=>{    Object.entries(a).filter(([k,v])=>b[k]!==v).forEach(([k,v])=>console.log(k)) */

//// dragging

const toDraggingElements = draggingStart => children => {
    const { dragKey, axis } = draggingStart
    if (!axis) return children
    const getDragKey = switchAxis(c => c.props.colKey, c => c.props.rowKey)(axis)
    const toDrEl = toDraggingElement(axis)
    return map(c => getDragKey(c) === dragKey ? toDrEl(c) : c)(children)
}

const switchAxis = (xF, yF) => axis => (
    axis === "x" ? xF : axis === "y" ? yF : never()
)

const getClientPos = switchAxis(ev => ev.clientX, ev => ev.clientY)
const getClientSize = switchAxis(el => el.clientWidth, el => el.clientHeight)
const stickyFrom = switchAxis("left", "top")
const stickyTo = switchAxis("right", "bottom")
const spanAllDir = switchAxis(
    k => ({ gridRow: spanAll, colKey: k }),
    k => ({ rowKey: k, gridColumn: spanAll })
)

const getDragElementData = switchAxis(element => {
    const rect = element.getBoundingClientRect()
    return { element, rectFrom: rect.left, rectTo: rect.right }
}, element => {
    const rect = element.getBoundingClientRect()
    return { element, rectFrom: rect.top, rectTo: rect.bottom }
})

const getKeyFromElement = switchAxis(
    el => el.getAttribute("data-col-key"),
    el => el.getAttribute("data-row-key")
)

const toDraggingElement = axis => child => cloneElement(child, {
    style: {
        ...child.props.style,
        position: "sticky",
        opacity: "0.33",
        [stickyFrom(axis)]: "var(--drag-from)",
        [stickyTo(axis)]: "var(--drag-to)",
    }
})

const getDropElements = ({ axis, dragKey }) => axis ? [$(GridCell, {
    key: `drop-${dragKey}`, ...spanAllDir(axis)(dragKey), className: "drop"
})] : []

////

const distinctBy = f => l => { //gives last?
    const entries = l.map(el => [f(el), el])
    const map = Object.fromEntries(entries)
    return entries.filter(([k, v]) => map[k] === v).map(([k, v]) => v)
}

const distinctByKey = switchAxis(
    distinctBy(el => el.getAttribute("data-col-key")),
    distinctBy(el => el.getAttribute("data-row-key"))
)

// const reversed = l => [...l].reverse()

const useGridDrag = ({ dragData, setDragData, gridElement, keys, enqueuePatch }) => {
    const { axis, isDown, clientPos, dragKey, patch, inElPos, rootStyle } = dragData
    const onMouseDown = useCallback(ev => {
        const axis = findFirstParent(el=>el.getAttribute("data-drag-handle"))(ev.target)
        if(!axis) return null
        const clientPos = getClientPos(axis)(ev)
        const cellElement = findFirstParent(el=> getKeyFromElement(axis)(el) && el)(ev.target)
        const { rectFrom } = getDragElementData(axis)(cellElement)
        const dragKey = getKeyFromElement(axis)(cellElement)
        const inElPos = clientPos - rectFrom
        setDragData({ axis, dragKey, inElPos, clientPos, isDown: true })
    }, [setDragData])
    const distinctElements = useMemo(
        () => axis && distinctByKey(axis)([...gridElement.children]),
        [axis, gridElement] // do not rely on finding particular elements
    )
    const move = useCallback(ev => {
        if (!axis) return
        const willClientPos = getClientPos(axis)(ev)
        const isDown = ev.buttons > 0
        const drops = distinctElements.map(getDragElementData(axis))
            .filter(r => r.rectFrom < willClientPos && willClientPos < r.rectTo)
        setDragData(was => {
            const dClientPos = willClientPos - was.clientPos
            const getKey = getKeyFromElement(axis)
            const willPatch = drops.map(drop => createPatch(keys, was.dragKey, getKey(drop.element), dClientPos)).find(p => p)
            return { ...was, clientPos: willClientPos, isDown, patch: willPatch || was.patch }
        })
    }, [setDragData, axis, distinctElements, keys])
    const doc = gridElement && gridElement.ownerDocument
    useEventListener(doc, "mousemove", isDown && move)
    useEventListener(doc, "mouseup", isDown && move)
    useLayoutEffect(() => {
        if (!axis) return
        const doGetKey = getKeyFromElement(axis)
        const dropPlaceElement = [...gridElement.children].find(el => doGetKey(el) === dragKey && !el.style.position)
        if (!dropPlaceElement) return
        const dropPlace = getDragElementData(axis)(dropPlaceElement)
        const targetPos = clientPos - inElPos
        const movedUp = /*true to left*/ targetPos < dropPlace.rectFrom
        const varDragFrom = movedUp ? "" : targetPos + "px"
        //console.log(targetPos, dropPlace)

        //const varDragFrom = targetPos+"px"
        const clientSize = getClientSize(axis)(doc.documentElement)
        const fromEnd = v => (clientSize - v) + "px"
        const varDragTo = fromEnd(targetPos + (dropPlace.rectTo - dropPlace.rectFrom))
        const rootStyle = { "--drag-from": varDragFrom, "--drag-to": varDragTo }
        //console.log(rootStyle)
        setDragData(was => ({ ...was, rootStyle }))
    }, [axis, dragKey, clientPos, inElPos, doc, setDragData, distinctElements])
    useEffect(() => {
        if (!isDown) setDragData(was => {
            const { patch } = was
            if (patch) enqueuePatch(patch)
            return {}
        })
    }, [isDown, setDragData, enqueuePatch])
    const draggingStart = useMemo(() => ({ axis, dragKey }), [dragKey, axis])
    return [rootStyle, onMouseDown, draggingStart]
}

/// Highlighter, may be moved out

const findFirstParent = get => el => el && get(el) || el && findFirstParent(get)(el.parentElement)

export function Highlighter({attrName}) {
    const [key,setKey] = useState(null)
    const [element,setElement] = useState(null)
    const move = useCallback(ev => {
        setKey(findFirstParent(el=>el.getAttribute(attrName))(ev.target))
    },[setKey])
    const style = key ? `div[${attrName}="${key}"]{background-color: var(--secondary-color);}` : ""
    const doc = element && element.ownerDocument
    useEventListener(doc, "mousemove", move)
    return $("style", { ref: setElement, dangerouslySetInnerHTML: { __html: style } })
}

///

export const components = {GridCell,GridRoot,Highlighter}