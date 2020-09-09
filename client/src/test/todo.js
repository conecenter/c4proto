
import {createElement as $} from 'react'

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

import { useSender, useSyncInput } from "../main/vdom-core.js"
import { TBodySortRoot, SortHandle } from "../main/vdom-sort.js"

const valueAt = key => prop => prop.at && prop.at[key]
const identityAt = key => prop => ({ parent: prop.at.identity, key })
const childrenAt = key => prop => map(k=>prop[k])(prop[key])
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

const map = f => l => l && l.map(f)
const head = l => l && l[0]

function SendingIconButton({identity,children}){
    const sender = useSender()
    return $(IconButton,{ onClick: ev=>sender.enqueue(identity,{}) }, children)
}

////

const rowsOf = childrenAt('rows')
const filtersOf = childrenAt('filters')
const cellsOf = childrenAt('cells')

const addOf = identityAt('add')
const removeOf = identityAt('remove')
const changeOf = identityAt('change')
const sortOf = identityAt('sort')

const sortValueOf = valueAt('sort')
const valueOf = valueAt('value')
const captionOf = valueAt('caption')



function ExampleField(prop){
    const identity = changeOf(prop)
    const patch = useSyncInput(identity, valueOf(prop), notDefer)
    return $(TextField,{...patch})
}

function ExampleRow(prop){
    const sender = useSender()
    return $(TableRow,{},
        $(TableCell,{},
            $(SortHandle,{},$(ArrowDownwardIcon))
        ),
        map(cell=>(
            $(TableCell,{},
                $(ExampleField,{...cell})
            )
        ))(cellsOf(prop)),
        $(TableCell,{},
            $(SendingIconButton,{ identity: removeOf(prop) }, $(DeleteIcon))
        ),
    )
}

function ExampleList(prop){
    const sender = useSender()
    return [
        $(Grid,{ container: true, spacing: 3 },
            map(field=>(
                $(Grid,{ item: true, xs: 3 },
                    captionOf(field),
                    $(ExampleField,{...field}),
                )
            ))(filtersOf(prop)),
        ),
        $(Grid,{ container: true, spacing: 3 },
            $(Grid,{ item: true, xs: 12 },
                $(TableContainer,{
                    component: Paper
                },[
                    $(Table,{},
                        $(TableHead,{},
                            $(TableRow,{},
                                $(TableCell,{}),
                                map(caption=>(
                                    $(TableCell,{},caption)
                                ))(map(captionOf)(cellsOf(head(rowsOf(prop))))),
                                $(TableCell,{},
                                    $(SendingIconButton,{ identity: addOf(prop) }, $(AddIcon))
                                ),
                            )
                        ),
                        $(TBodySortRoot,{
                            identity: sortOf(prop),
                            value: sortValueOf(prop),
                            children: map(row=>(
                                $(ExampleRow,{...row})
                            ))(rowsOf(prop))
                        })
                    )
                ])
            ),
        )
    ]
}

export const todoTransforms = {tp:{ExampleList}}