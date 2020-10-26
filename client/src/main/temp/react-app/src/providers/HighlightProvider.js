import React, { useContext, createContext, useState } from 'react'

const HighlightContext = createContext()

export function useHighlight() { return useContext(HighlightContext) }

export default function HighlightProvider({ children }) {

    const [style, setStyle] = useState({})


    function getStyles(divAttr) {
        return `div[${divAttr}]{background-color: var(--secondary-color);}`
    }

    const newRowStyle = style.rowKey ? getStyles(`data-row-key="${style.rowKey}"`) : ''

    const newColStyle = style.colKey ? getStyles(`data-col-key="${style.colKey}"`) : ''

    return (
        <HighlightContext.Provider value={setStyle}>
            <style dangerouslySetInnerHTML={{ __html: newRowStyle + newColStyle }} />
            {children}
        </HighlightContext.Provider>
    )
}
