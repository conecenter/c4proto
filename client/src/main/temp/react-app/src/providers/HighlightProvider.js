import React, { useContext, createContext, useState, useMemo } from 'react'

const HighlightContext = createContext()

export function useHighlight() { return useContext(HighlightContext) }

export default function HighlightProvider({ children }) {

    const [style, setStyle] = useState({})

    const newRowStyle = style.rowKey ? `div[data-row-key="${style.rowKey}"]{background-color: var(--secondary-color);}` : ''

    const newColStyle = style.colKey ? `div[data-col-key="${style.colKey}"]{background-color: var(--secondary-color);}` : ''

    return (
        <HighlightContext.Provider value={setStyle}>
            <style dangerouslySetInnerHTML={{ __html: newRowStyle + newColStyle }} />
            {children}
        </HighlightContext.Provider>
    )
}
