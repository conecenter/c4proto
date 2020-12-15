import React from 'react'

export default function ButtonElement({ caption, style, BGcolor, onClick }) {
    return (
        <div className="focusWrapper inlineBlock" style={style}>
            <button className={`goCenter ${BGcolor}Color`} onClick={onClick}>
                {typeof caption === "object" ? caption : <span>{caption}</span>}
            </button>
        </div>
    )
}