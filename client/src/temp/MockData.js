import { createElement as $ } from '../main/react-prod.js'
import { ImageElement } from '../temp/image.js'

export function MockData() {
    const srlIcon = $(ImageElement,{ src: "../temp/servicerequestline.svg", className: "rowIconSize", key: "image" })
    const meleqStr = $(Text,{ value: "MELEQ 11-Oct ● Vessel load" })
    const row = $("div",{ className: "descriptionRow", key: "row" }, srlIcon, meleqStr)
    return row
}

function Text({ value }) {
    return $("span",{ className: "text" }, value)
}
