import React, { cloneElement } from 'react'
import { ImageElement } from '../image'

export default function MockData() { }


export function ProjectCell() {
    const strs = ["CLDN Export", "DFDS Import", "CLDN Import", "Depot", "Line Equipment"]
    return getRandomText(strs)
}

export function StockCell() {
    const strs = ["CLDN RO-RO SA", "DFDS Seaway", "DSV Road A/S"]
    return getRandomText(strs)
}
export function NumMarkCell() {
    const strs = ["DSV9005", "BV9075", "BTEU3923433", "HAAU2611105", "DSV500225", "COMB2264114", "BTEU3909008", "DSV500236"]
    return getRandomText(strs)
}

export function LocationCell() {
    const chips = [
        <div className="chipItem greenColor greenColor-text" key="locChip">LANE 0</div>,
        <div className="chipItem greenColor greenColor-text" key="locChip">Q</div>,
        <div className="chipItem greenColor greenColor-text" key="locChip">E27</div>,
        <div className="chipItem greenColor greenColor-text" key="locChip">E09</div>,
        <div className="chipItem greenColor-light greenColor-light-text" key="locChip">F46</div>,
        <div className="chipItem greenColor-light greenColor-light-text" key="locChip">K34</div>,
        <div className="chipItem greenColor-light greenColor-light-text" key="locChip">B44</div>,
        <div className="chipItem greenColor-light greenColor-light-text" key="locChip">G52</div>,
        <div className="chipItem redColor redColor-text" key="locChip">Loaded</div>,
        <div className="chipItem redColor redColor-text" key="locChip">450122</div>,
        <div className="chipItem redColor redColor-text" key="locChip">TORC790</div>,
    ]

    return chips[Math.floor(Math.random() * chips.length)]
}

function getRandomText(strs) {
    const index = Math.floor(Math.random() * strs.length)

    return <Text value={strs[index]} key="rndText" />
}

export function ByCell() {
    const srlIcon = <ImageElement src="/icons/servicerequestline.svg" className="rowIconSize" key="image1" />
    const srIcon = <ImageElement src="/icons/service_request.svg" className="rowIconSize" key="image2" />
    const meleq11Str = <Text value="MELEQ 11-Oct" key="text1" />
    const meleq18Str = <Text value="MELEQ 18-Oct" key="text2" />
    const stripAllStr = <Text value="Strip All" key="text3" />
    const vesselLoadStr = <Text value="Vessel load" key="text4" />
    const elements = [srlIcon, srIcon, meleq11Str, meleq18Str, stripAllStr, vesselLoadStr]
    const elemCount = 3//Math.floor(Math.random() * elements.length)
    let indexes = []
    for (var i = 0; i < elemCount; i++) indexes[i] = Math.floor(Math.random() * elements.length);
    const elementsToShow = elements.reduce((acc, curr, i) => {
        const includes = indexes.includes(i)
        const current = cloneElement(curr, { ...curr.props, key: i })
        return includes ? (acc ? [...acc, current] : [current]) : acc
    }, [])
    const finalElements = elementsToShow.reduce((acc, curr, i) => {
        const current = cloneElement(curr, { className: `${curr.props.className} descriptionRow` })

        return acc.length > 0 ? [...acc, <Text value=" ● " key={`del${i}`} />, current] : [current]
    }, [])

    const row = <div className="descriptionRow " key="row" children={finalElements} />


    return row
}

export function Text({ value, className }) {
    return <span className={`${className ? className : ''} text`}>{value}</span>
}
