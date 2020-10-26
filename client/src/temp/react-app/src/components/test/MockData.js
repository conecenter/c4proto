import React from 'react'
import { ImageElement } from '../image'

export default function MockData() {
    const srlIcon = <ImageElement src="/icons/servicerequestline.svg" className="rowIconSize" key="image"/>
    const meleqStr = <Text value="MELEQ 11-Oct ● Vessel load" key="text" />
    const row =
        <div className="descriptionRow" key="row">
            {srlIcon}
            {meleqStr}
        </div>


    return row

}

function Text({ value }) {
    return <span className="text">{value}</span>
}
