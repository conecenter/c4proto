
import React from "react"

const { createElement: $ } = React

const fromKey = key => CSS.escape(`${key}-from`)
const toKey = key => CSS.escape(`${key}-to`)

const wrapRangeStr = (key,content) => (
    `${fromKey(key)}${content}${toKey(key)}`
)

const em = v => `${v}em`

const toRangeWidthStr = width => (
    width === "unbound" ? "auto" :
    width.tp === "bound" ? `minmax(${em(width.min)},${em(width.max)})` :
    never()
)

const getTemplateInner = slices => slices.map(({tp,sliceKey,...arg}) => (
    tp === "terminal" ? `${fromKey(sliceKey)}] ${toRangeWidthStr(arg.width)} [${toKey(sliceKey)}` :
    tp === "group" ? `${fromKey(sliceKey)} ${getTemplateInner(arg.slices)} ${toKey(sliceKey)}` :
    never()
)).join(" ")
const getTemplate = slices => `[${getTemplateInner(slices)}]`


export function PivotRoot({rows,cols,children}){
    const gridTemplateColumns = getTemplate(cols)
    const gridTemplateRows = getTemplate(rows)
    const style = { display: "grid", gridTemplateRows, gridTemplateColumns }
    return $("div", { style, children })
}

export function PivotCell({colKey,rowKey,className,children}){
    const gridArea = `${fromKey(rowKey)} / ${fromKey(colKey)} / ${toKey(rowKey)} / ${toKey(colKey)}`
    return $("div", { style: {gridArea}, className, children })
}

export const components = {PivotRoot,PivotCell}
