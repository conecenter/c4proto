
import {map,valueAt,childrenAt} from "../main/vdom-util.js"

import {createElement as $} from "../main/react-prod.js"

//import {useSync} from "../main/vdom-core.js"

const Table = "table"
const TableBody = "tbody"
const TableHead = "thead"
const TableRow = "tr"
const TableCell = "td"

const headOf = childrenAt("head")
const bodyOf = childrenAt("body")
const cellsOf = childrenAt("cells")
//const ReactOf = valueAt("React")

export function ListRoot(prop){
    //const {createElement:$} = ReactOf(prop)
    return $(Table,{},
        $(TableHead,{},
            map(({key,...row})=>(
                $(TableRow,{key},
                    map(cell=>(
                        $(TableCell,{key: cell.key},"???")
                    ))(cellsOf(row))
                )
            ))(headOf(prop))
        ),
        $(TableBody,{},
            map(({key,...row})=>(
                $(TableRow,{key},
                    map(cell=>(
                        $(TableCell,{key: cell.key},"???")
                    ))(cellsOf(row))
                )
            ))(bodyOf(prop))
        )
    )
}





