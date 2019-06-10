
const GlobalStyles = (()=>{
	let styles = {
		outlineWidth:"0.04em",
		outlineStyle:"solid",
		outlineColor:"blue",
		outlineOffset:"-0.1em",
		boxShadow:"0 0 0.3125em 0 rgba(0, 0, 0, 0.3)",
		borderWidth:"1px",
		borderStyle:"solid",
		borderSpacing:"0em",
	}	
	const DarkPrimaryColor = "#1976d2"
	const PrimaryColor = "#2196f3"
	return {...styles,
		DarkPrimaryColor, PrimaryColor
	}
})()
	
export default GlobalStyles