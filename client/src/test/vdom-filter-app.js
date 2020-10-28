
import React from "react"
import ReactDOM from "react-dom"

const {createElement:$,useState,useMemo} = React

import {FilterArea,FilterExpander,PopupContext} from "../main/vdom-filter.js"

function FilterButton({minWidth,optButtons}){
    return $("div",{style:{display:"flex",flexBasis:minWidth+"em",border:"1px solid blue"}},"B")
}
function FilterItem({nonEmpty,value}){
    return $("div",{style:{border:"1px solid blue",height:"100%",boxSizing: "border-box",}},value)
}

function ModeButton({setState,dataKey}){
    return $("button",{ onClick: ev=>setState(was=>({...was,[dataKey]:!was[dataKey]})) }, dataKey)
}

function App(){
    const [state,setState] = useState({})
    const {noFilters,showAll} = state
    const canHide = !showAll
    const [popup,setPopup] = useState(null)
    const identities = useMemo(()=>({lt:{},rt:{}}),[])

    return $(PopupContext.Provider, {value:[popup,setPopup]},
        $(ModeButton,{ key: "showAll", setState, dataKey: "showAll" }),
        $(ModeButton,{ key: "noFilters", setState, dataKey: "noFilters" }),
        $("div",{style:{height:"10em"}},"BEFORE"),
        $(FilterArea,{ key: "app",
            filters: noFilters ? [] : [
                $(FilterItem,{ key: 1, value: "1?", minWidth: 5, maxWidth:10, canHide }),
                $(FilterItem,{ key: 2, value: 2, minWidth: 10, maxWidth:10, }),
                $(FilterItem,{ key: 3, value: 3, minWidth: 5, maxWidth:10, }),
                $(FilterItem,{ key: 4, value: 4, minWidth: 5, maxWidth:10, }),
                $(FilterItem,{ key: 5, value: "5?", minWidth: 5, maxWidth:10, canHide }),
            ],
            buttons: [
                $(FilterExpander,{ key: 0, minWidth: 2, area: "lt", identity: identities.lt, optButtons: [
                    $(FilterButton,{ key: 3, minWidth: 4 }),
                    $(FilterButton,{ key: 2, minWidth: 4 }),
                ] }),
                $(FilterButton,{ key: 1, minWidth: 2, area: "lt" }),
                $(FilterButton,{ key: 2, minWidth: 2, area: "rt" }),
                $(FilterButton,{ key: 3, minWidth: 2, area: "rt" }),
                $(FilterExpander,{ key: 4, minWidth: 2, area: "rt", identity: identities.rt, optButtons: [
                    $(FilterButton,{ key: 7, minWidth: 4 }),
                    $(FilterButton,{ key: 6, minWidth: 4 }),
                    $(FilterButton,{ key: 5, minWidth: 4 }),
                ] }),
            ],
            centerButtonText: "of",
        }),
        "AFTER"
    )
}

const containerElement = document.createElement("div")
document.body.appendChild(containerElement)
ReactDOM.render($(App),containerElement)
