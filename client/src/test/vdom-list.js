

import {createElement as $, useMemo, useState, useLayoutEffect} from "../main/react-prod.js"
import {SortableHandle} from "../main/react-sortable-hoc-prod.js"

import {map,head,valueAt,childrenAt,identityAt,deleted,resolve} from "../main/vdom-util.js"
import {traverseOne} from "../main/vdom-core.js"
import {useSortRoot} from "../main/vdom-sort.js"

const Table = "table"
const TableBody = "tbody"
const TableHead = "thead"
const TableRow = "tr"
const TableCell = "td"

const headOf = childrenAt("head")
const bodyOf = valueAt("body")
const cellsOf = valueAt("cells")
const contentOf = valueAt("content")

const dragRowIdOf = identityAt('dragRow')
const setupModeOf = valueAt('setupMode')

const dragColIdOf = identityAt('dragCol')
const enableColDragOf = valueAt('enableColDrag')

const colPriorityOf = valueAt('colPriority')

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

const sortedKeysByPriority = resolve => sortedBy((a,b)=>colPriorityOf(resolve(a))-colPriorityOf(resolve(b)))

const excluding = keys => {
    const set = new Set(keys)
    return key => !set.has(key)
}

const useExpanded = () => {
    const [expanded,setExpanded] = useState({})
    const toggleExpanded = key => () => setExpanded(was => was[key] ? deleted({[key]:1})(was) : {...was, [key]:1} )
    return [expanded,toggleExpanded]
}



export function ListRoot(prop){
    const [applyDragRow,rowContainer] = useSortRoot(dragRowIdOf(prop))
    const [applyDragCol,colContainer] = useSortRoot(dragColIdOf(prop))
    const { ref: tableRef, width: tableWidth } = useWidth()
    const { ref: outerRef, width: outerWidth } = useWidth()
    const [expanded,toggleExpanded] = useExpanded()

    const rowKeys = applyDragRow(bodyOf(prop))

    const enableColDrag = setupModeOf(prop) === "colDrag"
    const enableRowDrag = setupModeOf(prop) === "rowDrag"

    const headRow = head(headOf(prop))

    const serverColKeys = cellsOf(headRow)
    const prColKeys = sortedKeysByPriority(resolve(headRow))(serverColKeys)
    const hiddenColKeys = useHiddenCols({tableWidth,outerWidth,serverColKeys:prColKeys})
    const sortedCellKeys = applyDragCol(serverColKeys).filter(excluding(hiddenColKeys))
    const hasHiddenCols = hiddenColKeys.length > 0

    const cellContent = row => cellKey => {
        const cell = resolve(row)(cellKey)
        return { key: cellKey, children: map(traverseOne)(map(resolve(cell))(contentOf(cell))) }
    }

    const headElem = $(TableHead,{ key: "head" },
        map(row=>{
            return colContainer({
                key: "colDrag", tp: TableRow,
                useDragHandle: true, axis: "x",
                children: map(key=>(
                    $(TableCell,{key},$(SortHandle,{},$(SortHandleIcon)))
                ))(sortedCellKeys)
            })
        })(enableColDrag ? [headRow]:[]),
        map(row=>{
            return $(TableRow,{ key: row.key },
                enableRowDrag && $(TableCell,{key:"drag"}),
                map(cellKey=>(
                    $(TableCell,cellContent(row)(cellKey))
                ))(sortedCellKeys),
                hasHiddenCols && $(TableCell,{key:"expand"})
            )
        })(headOf(prop))
    )

    const bodyElem = rowContainer({
        key: "body", tp: TableBody, useDragHandle: true,
        children: map(rowKey=>{
            const row = resolve(prop)(rowKey)
            const isExpanded = expanded[rowKey]
            const cellElements = [
                enableRowDrag && $(TableCell,{key:"drag"},$(SortHandle,{},$(SortHandleIcon))),
                map(cellKey=>(
                    $(TableCell,cellContent(row)(cellKey))
                ))(sortedCellKeys),
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
                            map(cellKey=>{
                                return $("div",{key: cellKey},
                                    $("label",{},"..."),
                                    $("span",cellContent(row)(cellKey))
                                )
                            })(hiddenColKeys)
                        )
                    ),
                ) :
                $(TableRow,{key: rowKey}, cellElements)
            )

        })(rowKeys)
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




