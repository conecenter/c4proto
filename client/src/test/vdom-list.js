

import {createElement as $, useMemo, useState, useLayoutEffect, cloneElement} from "../main/react-prod.js"
import {SortableHandle} from "../main/react-sortable-hoc-prod.js"

import {map,head as getHead,identityAt,deleted,keysOf,childByKey} from "../main/vdom-util.js"
import {useSortRoot} from "../main/vdom-sort.js"

const Table = "table"
const TableBody = "tbody"
const TableHead = "thead"
const TableRow = "tr"
const TableCell = "td"

const dragRowIdOf = identityAt('dragRow')
const dragColIdOf = identityAt('dragCol')

const SortHandle = SortableHandle(({children}) => children)

const SortHandleIcon = () => $("div",{},"o")

const useWidth = () => {
    const [width,setWidth] = useState(Infinity)
    const [element,setElement] = useState(null)
    const resizeObserver = useMemo(()=>new ResizeObserver(entries => {
        const entry = entries[0]
        if(entry) setWidth(Math.round(entry.contentRect.width))
    }))
    useLayoutEffect(()=>{
        element && resizeObserver.observe(element)
        return () => element && resizeObserver.unobserve(element)
    },[element])
    return { ref: setElement, width }
}

const sortedBy = f => l => l && [...l].sort(f)

const useHiddenCols = ({tableWidth,outerWidth,serverColKeys}) => {
    const [hiddenColCount,setHiddenColCount] = useState({count:0})
    const serverColCount = serverColKeys.length
    //console.log("render "+[tableWidth,outerWidth,serverColCount].join(","))
    useLayoutEffect(()=>setHiddenColCount(was=>{
        const d = outerWidth - tableWidth
        const justHidden = () => was.last && was.last.width === outerWidth && Date.now() - was.last.time < 2000
        const will = (
            d < 0 && was.count < serverColCount ? { count: was.count+1, last: { width: outerWidth, time: Date.now() } } :
            d > 0 && was.count > 0 && !justHidden() ? { ...was, count: was.count-1 } :
            was
        )
        //console.log("le",was,will,[tableWidth,outerWidth,serverColCount])
        return will
    }),[tableWidth,outerWidth,serverColCount])
    const hiddenColKeys = serverColKeys.slice(0,hiddenColCount.count) //memo?
    return hiddenColKeys
}

const sortedByPriority = sortedBy((a,b)=>a.props.priority-b.props.priority)

const excluding = keys => {
    const set = new Set(keys)
    return key => !set.has(key)
}

const useExpanded = () => {
    const [expanded,setExpanded] = useState({})
    const toggleExpanded = key => () => setExpanded(was => was[key] ? deleted({[key]:1})(was) : {...was, [key]:1} )
    return [expanded,toggleExpanded]
}



export function ListRoot({identity,body:wasBody,head,cols,setupMode}){
    const [applyDragRow,rowContainer] = useSortRoot(dragRowIdOf(identity))
    const [applyDragCol,colContainer] = useSortRoot(dragColIdOf(identity))
    const { ref: tableRef, width: tableWidth } = useWidth()
    const { ref: outerRef, width: outerWidth } = useWidth()
    const [expanded,toggleExpanded] = useExpanded()

    const body = childByKey(wasBody)(applyDragRow(keysOf(wasBody)))

    const enableColDrag = setupMode === "colDrag"
    const enableRowDrag = setupMode === "rowDrag"

    const colByKey = childByKey(cols)
    const serverColKeys = keysOf(sortedByPriority(cols))
    const hiddenColKeys = useHiddenCols({tableWidth,outerWidth,serverColKeys})
    const sortedCellKeys = applyDragCol(keysOf(cols)).filter(excluding(hiddenColKeys))
    const hasHiddenCols = hiddenColKeys.length > 0

    const headElem = $(TableHead,{ key: "head" },
        map(cols=>{
            return colContainer({
                key: "colDrag", tp: TableRow,
                useDragHandle: true, axis: "x",
                children: map(key=>(
                    $(TableCell,{key},$(SortHandle,{},$(SortHandleIcon)))
                ))(sortedCellKeys)
            })
        })(enableColDrag ? [cols]:[]),
        map(row=>(
            cloneElement(row,null,[
                enableRowDrag && $(TableCell,{key:"drag"}),
                childByKey(row.props.children)(sortedCellKeys),
                hasHiddenCols && $(TableCell,{key:"expand"})
            ])
        ))(head)
    )

    const bodyElem = rowContainer({
        key: "body", tp: TableBody, useDragHandle: true,
        children: map(row=>{
            const rowKey = row.key
            const isExpanded = expanded[rowKey]
            const cellByKey = childByKey(row.props.children)
            const cellElements = [
                enableRowDrag && $(TableCell,{key:"drag"},$(SortHandle,{},$(SortHandleIcon))),
                cellByKey(sortedCellKeys),
                hasHiddenCols && $(TableCell,{key:"expand"},
                    $(ExpandButton,{isExpanded,toggle:toggleExpanded(rowKey)})
                )
            ]

            return (
                hasHiddenCols && isExpanded ? $(Expanded,{key: rowKey},
                    $(TableRow,{ key: "row" }, cellElements),
                    $(TableRow,{ key: "expanded" },
                        enableRowDrag && $(TableCell,{key:"drag"}),
                        $(TableCell,{ key: "expanded", colSpan: sortedCellKeys.length+1 },
                            map(cell=>{
                                const cellKey = cell.key
                                return $("div",{key: cellKey},
                                    $("label",{},colByKey(cellKey).caption),
                                    $("span",{},cell.props.children)
                                )
                            })(cellByKey(hiddenColKeys))
                        )
                    ),
                ) :
                $(TableRow,{key: rowKey}, cellElements)
            )

        })(body)
    })

    return $("div",{ style: { overflow: "hidden" }, ref: outerRef },
        $(Table,{ key: "table", ref: tableRef }, headElem, bodyElem),
        //outerWidth+" "+tableWidth
    )
}

function Expanded({children}){
    return children
}

function ExpandButton({isExpanded,toggle}){
    return $("div",{ onClick: ev => toggle() }, isExpanded ? "V":">")
}




