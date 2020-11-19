import React from 'react'

export default function InputElement({ style, value }) {
    return (
        <div className="inputLike">
            <label>{value}</label>
            <div className="inputBox">
                <div className="inputSubbox">
                    <input type="text" />
                </div>
            </div>
        </div>
    )
}
