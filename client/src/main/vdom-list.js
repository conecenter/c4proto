

import { createElement as $, useMemo, useState, useLayoutEffect, cloneElement, useCallback, useEffect, memo } from "react"

import { map, head as getHead, identityAt, deleted, weakCache, never } from "./vdom-util.js"
import { useWidth, useEventListener, useSync } from "./vdom-hooks.js"

const dragRowIdOf = identityAt('dragRow')
const dragColIdOf = identityAt('dragCol')

const CELL_TYPES = {
    HEAD: "head",
    DRAG: "drag",
    EXPAND: "expand"
}

const GRID_CLASS_NAMES = {
    CELL: "tableCellContainer headerColor-border",
    HEADER: "tableHeadContainer headerColor",
}

//// col hiding

const sortedWith = f => l => l && [...l].sort(f)

//

const partitionVisibleCols = (cols, outerWidth) => {
    const fit = (count, accWidth) => {
        const col = cols[count]
        if (!col) return count
        const willWidth = accWidth + col.props.minWidth
        if (outerWidth < willWidth) return count
        return fit(count + 1, willWidth)
    }
    const count = fit(0, 0)
    return [cols.slice(0, count), cols.slice(count)]
}

const sortedByHideWill = sortedWith((a, b) => a.props.hideWill - b.props.hideWill)

const calcHiddenCols = (cols, outerWidth) => {
    const [visibleCols, hiddenCols] = partitionVisibleCols(sortedByHideWill(cols), outerWidth)
    const hasHiddenCols = hiddenCols.length > 0
    const hiddenColSet = hasHiddenCols && new Set(colKeysOf(hiddenCols))
    const hideElementsForHiddenCols = mode => (
        hasHiddenCols ? (children => children.filter(c => mode === hiddenColSet.has(c.props.colKey))) :
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
    const toExpanderElements = useCallback(on => !on ? (c => c) : children => children.map(c => {
        const { isExpander, rowKey } = c.props
        return isExpander && rowKey ? cloneElement(c, {
            onClick: ev => setExpandedItem(rowKey, v => !v),
            expander: expanded[rowKey] ? 'expanded' : 'collapsed',
        }) : c
    }), [expanded, setExpandedItem])
    const getExpandedCells = useCallback(({ cols, rowKeys, children }) => {
        if (cols.length <= 0) return []
        const posStr = (rowKey, colKey) => rowKey + colKey
        const expandedByPos = Object.fromEntries(
            children.filter(c => expanded[c.props.rowKey])
                .map(c => [posStr(c.props.rowKey, c.props.colKey), c])
        )
        return rowKeys.filter(rowKey => expanded[rowKey]).map(rowKey => {
            const pairs = cols.map(col => {
                const cell = expandedByPos[posStr(rowKey, col.props.colKey)]
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

const hideExpander = hasHiddenCols => hasHiddenCols ? (l => l) : (l => l.filter(c => !c.props.isExpander))

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
    const colByKey = Object.fromEntries(cols.map(c => [c.props.colKey, c]))
    return colKeys => colKeys.map(k => colByKey[k])
}

//// main

const getGridRow = ({ rowKey, rowKeyMod }) => rowKey + (rowKeyMod || '')

const spanAll = "1 / -1"

export function GridCell({ children, rowKey, rowKeyMod, colKey, isExpander, expander, dragHandle, noDefCellClass, className: argClassName, ...props }) {
    const gridRow = getGridRow({ rowKey, rowKeyMod })
    const gridColumn = colKey
    const style = { ...props.style, gridRow, gridColumn }
    const expanderProps = isExpander ? { 'data-expander': expander || 'passive' } : {}
    const className = noDefCellClass ? argClassName : `${argClassName} ${GRID_CLASS_NAMES.CELL}`
    const colKeyOpt = spanAll === colKey ? {} : { 'data-col-key': colKey }
    return $("div", { ...props, ...expanderProps, ...colKeyOpt, 'data-row-key': rowKey, "data-drag-handle": dragHandle, style, className }, children)
}

const pos = (rowKey, colKey) => ({ key: rowKey + colKey, rowKey, colKey })

const colKeysOf = children => children.map(c => c.props.colKey)

export function GridCol(props) {
    return []
}

const getGidTemplateRows = rows => rows.map(o => `[${getGridRow(o)}] auto`).join(" ")
const getGridTemplateColumns = columns => columns.map(c => {
    const key = c.props.colKey
    const width = `minmax(${c.props.minWidth}em,${c.props.maxWidth}em)`
    return `[${key}] ${width}`
}).join(" ")

export function GridRoot({ identity, rowKeys, cols, children }) {
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
        ...(hasDragRow ? [{ rowKey: CELL_TYPES.DRAG }]:[]),
        { rowKey: CELL_TYPES.HEAD },
        ...expandRowKeys(expanded)(patchedRowKeys)
    ]), [hasDragRow, expanded, patchedRowKeys])

    const outerWidth = useWidth(gridElement)
    const { hasHiddenCols, hideElementsForHiddenCols } =
        useMemo(() => calcHiddenCols(cols, outerWidth), [cols, outerWidth])
    const gridTemplateColumns = useMemo(() => getGridTemplateColumns(
        hideExpander(hasHiddenCols)(hideElementsForHiddenCols(false)(patchedCols))
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
    return $("div", { onMouseDown, style, className: "grid", ref: setGridElement }, allChildren)
}

const getAllChildren = ({children,rowKeys,cols,draggingStart,hasHiddenCols,hideElementsForHiddenCols,toExpanderElements,getExpandedCells}) => {
    const headElements = map(col => $(GridCell, {
        ...pos(CELL_TYPES.HEAD, col.props.colKey),
        className: GRID_CLASS_NAMES.HEADER
    }, col.props.caption))(hideExpander(hasHiddenCols)(cols))

    const dropElements = getDropElements(draggingStart)

    const expandedElements = getExpandedCells({
        rowKeys, children, cols: hideElementsForHiddenCols(true)(cols),
    }).map(([rowKey, pairs]) => {
        return $(GridCell, { ...pos(rowKey, spanAll), rowKeyMod: "-expanded", style: { display: "flex", flexFlow: "row wrap" } },
            pairs.map(([col, cell]) => (
                $("div",{
                    key: col.key,
                    style: { flexBasis: `${col.props.minWidth}em` },
                    className: "inputLike"
                },[
                    $("label", {}, col.props.caption),
                    $("div", {}, cell.props.children),
                ])
            ))
        )
    })

    const allChildren = toExpanderElements(hasHiddenCols)([...dropElements, ...toDraggingElements(draggingStart)(hideElementsForHiddenCols(false)([
        ...headElements, ...children, ...expandedElements
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
const spanAllDir = switchAxis(k => pos(spanAll, k), k => pos(k, spanAll))

const getDragElementData = switchAxis(el => {
    const rect = el.getBoundingClientRect()
    return { gridStart: el.style.gridColumnStart, rectFrom: rect.left, rectTo: rect.right }
}, el => {
    const rect = el.getBoundingClientRect()
    return { gridStart: el.style.gridRowStart, rectFrom: rect.top, rectTo: rect.bottom }
})

const getGridStart = switchAxis(el => el.style.gridColumnStart, el => el.style.gridRowStart)

const toDraggingElement = axis => child => cloneElement(child, {
    style: {
        ...child.props.style,
        position: "sticky",
        opacity: "0.33",
        [stickyFrom(axis)]: "var(--drag-from)",
        [stickyTo(axis)]: "var(--drag-to)",
    }
})

const getDropElements = ({ axis, dragKey }) => axis ? [$(GridCell, { ...spanAllDir(axis)(dragKey), className: "drop" })] : []

////

const distinctBy = f => l => { //gives last?
    const entries = l.map(el => [f(el), el])
    const map = Object.fromEntries(entries)
    return entries.filter(([k, v]) => map[k] === v).map(([k, v]) => v)
}

const distinctByStart = switchAxis(
    distinctBy(el => el.style.gridColumnStart),
    distinctBy(el => el.style.gridRowStart)
)

// const reversed = l => [...l].reverse()

const useGridDrag = ({ dragData, setDragData, gridElement, keys, enqueuePatch }) => {
    const { axis, isDown, clientPos, dragKey, patch, inElPos, rootStyle } = dragData
    const onMouseDown = useCallback(ev => {
        const axis = findFirstParent(el=>el.getAttribute("data-drag-handle"))(ev.target)
        if(!axis) return null
        const clientPos = getClientPos(axis)(ev)
        const cellElement = findFirstParent(el=> getGridStart(axis)(el) && el)(ev.target)
        const { gridStart: dragKey, rectFrom } = getDragElementData(axis)(cellElement)
        const inElPos = clientPos - rectFrom
        setDragData({ axis, dragKey, inElPos, clientPos, isDown: true })
    }, [setDragData])
    const distinctElements = useMemo(
        () => axis && distinctByStart(axis)([...gridElement.children]),
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
            const willPatch = drops.map(drop => createPatch(keys, was.dragKey, drop.gridStart, dClientPos)).find(p => p)
            return { ...was, clientPos: willClientPos, isDown, patch: willPatch || was.patch }
        })
    }, [setDragData, axis, distinctElements, keys])
    const doc = gridElement && gridElement.ownerDocument
    useEventListener(doc, "mousemove", isDown && move)
    useEventListener(doc, "mouseup", isDown && move)
    useLayoutEffect(() => {
        if (!axis) return
        const doGetGridStart = getGridStart(axis)
        const dropPlaceElement = [...gridElement.children].find(el => doGetGridStart(el) === dragKey && !el.style.position)
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