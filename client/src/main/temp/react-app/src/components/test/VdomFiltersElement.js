import React from 'react'
import { FilterArea, FilterExpander, PopupContext } from '../../main/vdom-filter.js'
import InputElement from '../input'
import ButtonElement from '../button'

const { createElement: $, useState, useMemo } = React

export default function VdomFiltersElement() {



    function FilterButton({ minWidth, optButtons }) {
        return <ButtonElement style={{ display: "flex", flexBasis: minWidth + "em" }} caption="Button"/>
        return $("div", { style: { display: "flex", flexBasis: minWidth + "em", border: "1px solid blue" } }, "B")
    }
    function FilterItem({ nonEmpty, value }) {

        return (
            <InputElement value={value} style={{ border: "1px solid blue", height: "100%", boxSizing: "border-box" }} />
            // <div className="inputLike" style={{ border:"1px solid blue",height:"100%",boxSizing: "border-box" }}>
            //     <label>{value}</label>
            //     <input style={{ width: "100%" }}></input>
            // </div>
        )
    }

    function ModeButton({ setState, dataKey }) {
        return $("button", { onClick: ev => setState(was => ({ ...was, [dataKey]: !was[dataKey] })) }, dataKey)
    }


    const [state, setState] = useState({})
    const { noFilters, showAll } = state
    const canHide = !showAll
    const [popup, setPopup] = useState(null)
    const identities = useMemo(() => ({ lt: {}, rt: {} }), [])

    return $(PopupContext.Provider, { value: [popup, setPopup] },
        $(ModeButton, { key: "showAll", setState, dataKey: "showAll" }),
        $(ModeButton, { key: "noFilters", setState, dataKey: "noFilters" }),
        $("div", { style: { height: "10em" } }, "BEFORE"),
        $(FilterArea, {
            key: "app",
            filters: noFilters ? [] : [
                $(FilterItem, { key: 1, value: "Number/Marking", minWidth: 7, maxWidth: 10, canHide }),
                $(FilterItem, { key: 2, value: "Location", minWidth: 5, maxWidth: 10, }),
                $(FilterItem, { key: 3, value: "Location Feature", minWidth: 8, maxWidth: 10, }),
                $(FilterItem, { key: 4, value: "Mode", minWidth: 3, maxWidth: 10, }),
                $(FilterItem, { key: 5, value: "From", minWidth: 3, maxWidth: 10, canHide }),
            ],
            buttons: [
                $(FilterExpander, {
                    key: 0, minWidth: 2, area: "lt", identity: identities.lt, optButtons: [
                        $(FilterButton, { key: 3, minWidth: 4 }),
                        $(FilterButton, { key: 2, minWidth: 4 }),
                    ]
                }),
                $(FilterButton, { key: 1, minWidth: 2, area: "lt" }),
                $(FilterButton, { key: 2, minWidth: 2, area: "rt" }),
                $(FilterButton, { key: 3, minWidth: 2, area: "rt" }),
                $(FilterExpander, {
                    key: 4, minWidth: 2, area: "rt", identity: identities.rt, optButtons: [
                        $(FilterButton, { key: 7, minWidth: 4 }),
                        $(FilterButton, { key: 6, minWidth: 4 }),
                        $(FilterButton, { key: 5, minWidth: 4 }),
                    ]
                }),
            ],
            centerButtonText: "of",
        })
    )

}
