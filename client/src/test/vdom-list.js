

import {createElement as $, useMemo, useState, useLayoutEffect} from "../main/react-prod.js"
import {SortableHandle} from "../main/react-sortable-hoc-prod.js"

import {map,head,valueAt,childrenAt,identityAt} from "../main/vdom-util.js"
import {traverseOne} from "../main/vdom-core.js"
import {SortContainer,useSortRoot,sortChildren} from "../main/vdom-sort.js"

const Table = "table"
const TableBody = "tbody"
const TableHead = "thead"
const TableRow = "tr"
const TableCell = "td"

const headOf = childrenAt("head")
const bodyOf = childrenAt("body")
const cellsOf = childrenAt("cells")
const contentOf = valueAt("content")

const rowOrderIdOf = identityAt('rowOrder')
const rowOrderOf = valueAt('rowOrder')
const setupModeOf = valueAt('setupMode')

const colOrderIdOf = identityAt('colOrder')
const colOrderOf = valueAt('colOrder')
const enableColDragOf = valueAt('enableColDrag')

const colPriority = valueAt('colPriority')

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
    console.log("render "+[tableWidth,outerWidth,serverColCount].join(","))
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
    const hideSet = useMemo(()=>{
        const keys = serverColKeys.slice(0,hiddenColCount.count)
        return new Set(keys)
    }, [serverColKeys,hiddenColCount.count])
    return hideSet
}

const useHiddenColsOuter = ({tableWidth,outerWidth,cols}) => {
    const sortedByPriority = sortedBy((a,b)=>colPriority(a)-colPriority(b))
    const serverColKeys = sortedByPriority(cols).map(c=>c.key)
    const hideSet = useHiddenCols({tableWidth,outerWidth,serverColKeys})
    return cells => cells.filter(cell => !hideSet.has(cell.key))
}

export function ListRoot(prop){
    const [patchedRowOrder,onRowOrderEnd] = useSortRoot(rowOrderIdOf(prop),rowOrderOf(prop))
    const [patchedColOrder,onColOrderEnd] = useSortRoot(colOrderIdOf(prop),colOrderOf(prop))
    const sortedRows = sortChildren(patchedRowOrder,bodyOf(prop))

    const enableColDrag = setupModeOf(prop) === "colDrag"
    const enableRowDrag = setupModeOf(prop) === "rowDrag"

    const headRow = head(headOf(prop))


    const { ref: tableRef, width: tableWidth } = useWidth()
    const { ref: outerRef, width: outerWidth } = useWidth()
    const filterHidden = useHiddenColsOuter({tableWidth,outerWidth,cols:cellsOf(headRow)})

    const filterSortCells = row => filterHidden(sortChildren(patchedColOrder,cellsOf(row)))


    const headElem = $(TableHead,{ key: "head" },
        map(row=>{
            const sortedCells = filterSortCells(row)
            return $(SortContainer,{
                key: "colDrag", tp: TableRow,
                useDragHandle: true, onSortEnd: onColOrderEnd, axis: "x",
                children: map(cell=>(
                    $(TableCell,{key:cell.key},$(SortHandle,{},$(SortHandleIcon)))
                ))(sortedCells)
            })
        })(enableColDrag ? [headRow]:[]),
        map(row=>{
            const sortedCells = filterSortCells(row)
            return $(TableRow,{ key: row.key },
                enableRowDrag && $(TableCell,{key:"drag"}),
                map(cell=>(
                    $(TableCell,{key: cell.key},map(traverseOne)(contentOf(cell)))
                ))(sortedCells)
            )
        })(headOf(prop))
    )

    const bodyElem = $(SortContainer,{
        key: "body", tp: TableBody,
        useDragHandle: true, onSortEnd: onRowOrderEnd,
        children: map(row=>{
            const sortedCells = filterSortCells(row)
            return $(TableRow,{key: row.key},
                enableRowDrag && $(TableCell,{key:"drag"},$(SortHandle,{},$(SortHandleIcon))),
                map(cell=>(
                    $(TableCell,{key: cell.key},map(traverseOne)(contentOf(cell)))
                ))(sortedCells)
            )
        })(sortedRows)
    })

    return $("div",{ style: { overflow: "hidden" }, ref: outerRef },
        $(Table,{ key: "table", ref: tableRef }, headElem, bodyElem),
        outerWidth+" "+tableWidth
    )
}






