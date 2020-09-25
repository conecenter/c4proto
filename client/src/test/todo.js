
import {createElement as $, Children} from 'react'

import Table from '@material-ui/core/Table'
import TableBody from '@material-ui/core/TableBody'
import TableCell from '@material-ui/core/TableCell'
import TableContainer from '@material-ui/core/TableContainer'
import TableHead from '@material-ui/core/TableHead'
import TableRow from '@material-ui/core/TableRow'
import Paper from '@material-ui/core/Paper'
import IconButton from '@material-ui/core/IconButton'
import DeleteIcon from '@material-ui/icons/Delete'
import AddIcon from '@material-ui/icons/Add'
import TextField from '@material-ui/core/TextField'
import ArrowDownwardIcon from '@material-ui/icons/ArrowDownward'
import Grid from '@material-ui/core/Grid'
import {SortableHandle} from 'react-sortable-hoc'

import { useSender, useSyncInput } from "../main/vdom-core.js"
import { useSortRoot } from "../main/vdom-sort.js"
import { map, head, identityAt } from "../main/vdom-util.js"

/*
{
    const find = ctx => ctx.key ? ctx : find(ctx.parent)
    const ctx = find(prop.at.identity).parent
    prop[key] && prop[key].map(k=>{

        const identity = { key: "at", parent: { key: k, parent: ctx }}
        return { ...prop[k], at: { ...prop[k].at, identity } }
    })
}*/
const notDefer = _=>false

function SendingIconButton({identity,children}){
    const sender = useSender()
    return $(IconButton,{ onClick: ev=>sender.enqueue(identity,{}) }, children)
}

////

const addIdOf = identityAt('add')
const removeIdOf = identityAt('remove')
const changeIdOf = identityAt('change')
const sortIdOf = identityAt('sort')

const SortHandle = SortableHandle(({children}) => children)

function ExampleField({identity,value}){
    const patch = useSyncInput(changeIdOf(identity), value, notDefer)
    return $(TextField,{...patch})
}

function ExampleRow(prop){
    const {identity,cells} = prop
    const resolve = resolveOne(prop)
    return $(TableRow,{},
        $(TableCell,{key:"drag"},
            $(SortHandle,{},$(ArrowDownwardIcon))
        ),
        map(({key,...cell})=>(
            $(TableCell,{key},
                $(ExampleField,{...cell})
            )
        ))(map(resolve)(cells)),
        $(TableCell,{key:"remove"},
            $(SendingIconButton,{ identity: removeIdOf(identity) }, $(DeleteIcon))
        ),
    )
}

function ExampleList(prop){
    const {identity,rows,filters} = prop
    const resolve = resolveOne(prop)
    const [applySort,container] = useSortRoot(sortIdOf(identity)) // ??? .map(c=>c.key)
    return [
        $(Grid,{ key: "filters", container: true, spacing: 3 },
            map(({key,...field})=>(
                $(Grid,{ key, item: true, xs: 3 },
                    field.caption,
                    $(ExampleField,{...field}),
                )
            ))(map(resolve)(filters)),
        ),
        $(Grid,{ key: "table", container: true, spacing: 3 },
            $(Grid,{ item: true, xs: 12 },
                $(TableContainer,{
                    component: Paper
                },[
                    $(Table,{ key: "table" },
                        $(TableHead,{},
                            $(TableRow,{},
                                $(TableCell,{key:"drag"}),
                                map(cProp=>(
                                    $(TableCell,{key:cProp.key},cProp.caption)
                                ))(cellsOf(head(map(resolve)(rows)))),
                                $(TableCell,{key:"remove"},
                                    $(SendingIconButton,{ identity: addIdOf(identity) }, $(AddIcon))
                                ),
                            )
                        ),
                        container({
                            tp: TableBody, useDragHandle: true,
                            children: map(row=>(
                                $(ExampleRow,{...row})
                            ))(sortedRows/* ??? */)
                        })
                    )
                ])
            ),
        )
    ]
}

export const todoTransforms = {tp:{ExampleList}}