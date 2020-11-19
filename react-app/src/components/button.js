import React from 'react'

export default function ButtonElement({ caption, style }) {
    return (
        <div class="focusWrapper inlineBlock" style={style}>
            <button class="goCenter greenColor">
                <span class="text">{caption}</span>
            </button>
        </div>
    )
}
