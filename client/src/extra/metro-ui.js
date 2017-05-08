"use strict";
import React from 'react'
import {pairOfInputAttributes}  from "../main/vdom-util"
/*
todo:
extract mouse/touch to components https://facebook.github.io/react/docs/jsx-in-depth.html 'Functions as Children'
jsx?
*/

export default function MetroUi({log,sender,setTimeout,clearTimeout,uglifyBody,press,svgSrc,addEventListener,removeEventListener,getComputedStyle,fileReader,getPageYOffset,getInnerHeight}){
	const GlobalStyles = (()=>{
		let styles = {
			outlineWidth:"0.04em",
			outlineStyle:"solid",
			outlineColor:"blue",
			outlineOffset:"-0.1em",
			boxShadow:"0 0 0.3125em 0 rgba(0, 0, 0, 0.3)",
			borderWidth:"0.04em",
			borderStyle:"solid",
			borderSpacing:"0em",
		}
		const update = (newStyles) => styles = {...styles,...newStyles}
		return {...styles,update};
	})()
	const FlexContainer = ({flexWrap,children,style}) => React.createElement("div",{style:{
		display:'flex',
		flexWrap:flexWrap?flexWrap:'nowrap',
		...style
		}},children);
	const FlexElement = ({expand,minWidth,maxWidth,style,children})=>React.createElement("div",{style:{
		flexGrow:expand?'1':'0',
		flexShrink:'1',
		minWidth:'0px',
		flexBasis:minWidth?minWidth:'auto',
		maxWidth:maxWidth?maxWidth:'auto',
		...style
	}},children);
	const ButtonElement=React.createClass({
		getInitialState:function(){
			return {mouseOver:false,touch:false};
		},
		mouseOver:function(){
			this.setState({mouseOver:true});
			if(this.props.onMouseOver)
				this.props.onMouseOver();
		},
		mouseOut:function(){
			this.setState({mouseOver:false});
			if(this.props.onMouseOut)
				this.props.onMouseOut();
		},
		onTouchStart:function(e){
			this.setState({touch:true});
		},
		onTouchEnd:function(e){		
			this.setState({touch:false,mouseOver:false});		
		},
		onClick:function(e){			
			if(this.props.onClick){
				setTimeout(function(){this.props.onClick(e)}.bind(this),(this.props.delay?parseInt(this.props.delay):0));
			}				
		},
		componentWillReceiveProps:function(nextProps){
			this.setState({mouseOver:false,touch:false});
		},
		render:function(){		
			const style={
				border:'none',
				cursor:'pointer',
				paddingInlineStart:'0.4em',
				paddingInlineEnd:'0.4em',
				padding:'0 1em',
				minHeight:'2em',
				minWidth:'1em',
				fontSize:'1em',
				alignSelf:'center',
				outline:this.state.touch?`${GlobalStyles.outlineWidth} ${GlobalStyles.outlineStyle} ${GlobalStyles.outlineColor}`:'none',
				outlineOffset:GlobalStyles.outlineOffset,
				backgroundColor:this.state.mouseOver?"#ffffff":"#eeeeee",
				...this.props.style,
				...(this.state.mouseOver?this.props.overStyle:null)
			}	
			return React.createElement("button",{style,onMouseOver:this.mouseOver,onMouseOut:this.mouseOut,onClick:this.onClick,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children);
		}
	});
	const MenuBarElement=React.createClass({
		getInitialState:function(){
			return {fixedHeight:"",scrolled:false}
		},
		process:function(){
			if(!this.el) return;
			const height = this.el.getBoundingClientRect().height + "px";
			if(height !== this.state.fixedHeight)
				this.setState({fixedHeight:height});
		},
		onScroll:function(){
			const scrolled = getPageYOffset()>0;
			if(!this.state.scrolled&&scrolled) this.setState({scrolled}) 
			else if(this.state.scrolled&&!scrolled) this.setState({scrolled})
		},
		componentWillUnmount:function(){
			removeEventListener("scroll",this.onScroll);
		},
		componentDidUpdate:function(){
			this.process();
		},
		componentDidMount:function(){
			this.process();
			addEventListener("scroll",this.onScroll);
		},
		render:function(){
			const style = {
				height:this.state.fixedHeight				
			}
			const barStyle = {
				display:'flex',
				flexWrap:'nowrap',
				justifyContent:'flex-start',
				backgroundColor:'#2196f3',
				verticalAlign:'middle',
				position:"fixed",
				width:"100%",
				zIndex:"6669",
				top:"0rem",
				boxShadow:this.state.scrolled?GlobalStyles.boxShadow:"",
				...this.props.style
			}
			return React.createElement("div",{style:style},				
				React.createElement("div",{style:barStyle,ref:ref=>this.el=ref},this.props.children)
			)
		}		
	});
	const MenuDropdownElement = React.createClass({
		getInitialState:function(){
			return {maxHeight:""};
		},
		calcMaxHeight:function(){
			if(!this.el) return;			
			const elTop = this.el.getBoundingClientRect().top;
			const innerHeight = getInnerHeight();
			if(this.props.isOpen&&parseFloat(this.state.maxHeight)!=innerHeight - elTop)						
				this.setState({maxHeight:innerHeight - elTop + "px"});				
		},		
		render:function(){
			return React.createElement("div",{
				ref:ref=>this.el=ref,
				style: {
					position:'absolute',					
					minWidth:'7em',
					boxShadow:GlobalStyles.boxShadow,
					zIndex:'6670',
					transitionProperty:'all',
					transitionDuration:'0.15s',
					transformOrigin:'50% 0%',
					borderWidth:GlobalStyles.borderWidth,
					borderStyle:GlobalStyles.borderStyle,
					borderColor:"#2196f3",					
					maxHeight:this.state.maxHeight,					
					...this.props.style
				}
			},this.props.children);			
		}				
	});

	const FolderMenuElement=React.createClass({
		getInitialState:function(){
			return {mouseEnter:false,touch:false};
		},
		mouseEnter:function(e){
			this.setState({mouseEnter:true});
		},
		mouseLeave:function(e){
			this.setState({mouseEnter:false});
		},
		onClick:function(e){
		    if(this.props.onClick)
		        this.props.onClick(e);
			e.stopPropagation();			
		},
		render:function(){		
			const selStyle={
				position:'relative',
                backgroundColor:'#c0ced8',
                whiteSpace:'nowrap',
                paddingRight:'0.8em',
				cursor:"pointer",
				...this.props.style,
				...(this.state.mouseEnter?this.props.overStyle:null)
			};						
				
			return React.createElement("div",{				
			    style:selStyle,
			    onMouseEnter:this.mouseEnter,
			    onMouseLeave:this.mouseLeave,
			    onClick:this.onClick			   
			},this.props.children);
		}
	});
	const ExecutableMenuElement=React.createClass({
		getInitialState:function(){
			return {mouseEnter:false};
		},
		mouseEnter:function(e){
			this.setState({mouseEnter:true});
		},
		mouseLeave:function(e){
			this.setState({mouseEnter:false});
		},
		onClick:function(e){
			if(this.props.onClick)
				this.props.onClick(e);
		},
		render:function(){
			const newStyle={
                minWidth:'7em',
                height:'2.5em',
                backgroundColor:'#c0ced8',
                cursor:'pointer',
				...this.props.style,
				...(this.state.mouseEnter?this.props.overStyle:null)
			};       
		return React.createElement("div",{
            style:newStyle,    
            onMouseEnter:this.mouseEnter,
            onMouseLeave:this.mouseLeave,
            onClick:this.onClick
		},this.props.children);
		}
	});
	const TabSet=({style,children})=>React.createElement("div",{style:{
		borderBottomWidth:GlobalStyles.borderWidth,
		borderBottomStyle:GlobalStyles.borderStyle,		         
		overflow:'hidden',
		display:'flex',
		marginTop:'0rem',
		...style
	}},children);
	const DocElement=React.createClass({
		componentDidMount:function(){ uglifyBody(this.props.style) },
		render:function(){		
			return React.createElement("div");
		}	
	});
	const GrContainer= ({style,children})=>React.createElement("div",{style:{
		boxSizing:'border-box',           
		fontSize:'0.875em',
		lineHeight:'1.1em',
		margin:'0px auto',
		paddingTop:'0.3125em',
		...style
	}},children);
	const FlexGroup = React.createClass({
		getInitialState:function(){
			return {rotated:false,captionOffset:"",containerMinHeight:""};
		},
		shouldRotate:function(){
			const fToS=this.groupEl.getBoundingClientRect().width/parseInt(getComputedStyle(this.groupEl).fontSize);
			const ftosS = parseInt(this.props.ftos);
			if(!ftosS) return false;
			if(fToS<ftosS && this.state.rotated){
				this.setState({rotated:false});
				return true;
			}
			else if(fToS> ftosS && !this.state.rotated){
				this.setState({rotated:true});
				return true;
			}	
			return false;
		},
		recalc:function(){			
			const block=this.captionEl.getBoundingClientRect();
			const cs=getComputedStyle(this.groupEl);			
			const containerMinHeight=(Math.max(block.height,block.width) + parseFloat(cs.paddingBottom||0) + parseFloat(cs.paddingTop||0)) +'px';			
			const captionOffset=(-Math.max(block.height,block.width))+'px';
			this.setState({captionOffset,containerMinHeight});
			this.shouldRotate();
		},
		componentDidMount:function(){
			if(this.props.caption){
				this.recalc();
				addEventListener("resize",this.recalc);
			}					
		},
		componentDidUpdate:function(prevProps,prevState){			
			if(prevProps.caption!=this.props.caption)
				this.recalc();
		},
		componentWillUnmount:function(){
			if(this.props.caption){
				removeEventListener("resize",this.recalc);
			}
		},
		render:function(){			
			const style={
				backgroundColor:'white',
				borderColor:'#b6b6b6',
				borderStyle:'dashed',
				borderWidth:GlobalStyles.borderWidth,
				margin:'0.4em',
				padding:this.props.caption?'0.5em 1em 1.25em 1.6em':'0.5em 0.5em 1.25em 0.5em',
				minHeight:this.state.containerMinHeight,
				...this.props.style
			};
			const captionStyle={
				color:"#727272",
				lineHeight:"1",
				marginLeft:this.state.rotated?"calc("+this.state.captionOffset+" - 1.7em)":"1em",
				position:this.state.rotated?"absolute":"static",
				transform:this.state.rotated?"rotate(-90deg)":"none",
				transformOrigin:"100% 0px",
				whiteSpace:"nowrap",
				marginTop:"1.5em",
				fontSize:"0.875em",
				display:"inline-block",
				...this.props.captionStyle
			};
			const captionEl = this.props.caption? React.createElement("div",{ref:ref=>this.captionEl=ref,style:captionStyle,key:"caption"},this.props.caption): null;
			return React.createElement("div",{ref:ref=>this.groupEl=ref,style:style},[			
				captionEl,
				this.props.children
			])
		}	
	}); 
	FlexGroup.defaultProps = {
		ftos:"16"		
	};
	const Chip = ({value,style,children})=>React.createElement('input',{style:{
		fontWeight:'bold',
		fontSize:'1.4em',
		color:'white',
		textAlign:'center',
		borderRadius:'0.58em',
		border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #eee`,		
		backgroundColor:"white",
		cursor:'default',
		width:'3.8em',
		display:'block',
		marginBottom:'0.1rem',
		...style
	},readOnly:'readonly',value:(children || value)},null);	
	const VKTd = React.createClass({
		getInitialState:function(){
			return {touch:false,mouseDown:false};
		},
		onClick:function(ev){
			if(this.props.onClick){
				this.props.onClick(ev);
				return;
			}
			if(this.props.fkey) press(this.props.fkey)
			if(this.props.onClickValue)
				this.props.onClickValue("key",this.props.fkey);
		},
		onTouchStart:function(e){
			this.setState({touch:true});
		},
		onTouchEnd:function(e){
			this.setState({touch:false});
		},
		onMouseDown:function(){this.setState({mouseDown:true})},
		onMouseUp:function(){this.setState({mouseDown:false})},
		render:function(){
			const bStyle={
				height:'100%',
				width:'100%',
				border:'none',
				fontStyle:'inherit',
				fontSize:'0.7em',
				backgroundColor:'inherit',
				verticalAlign:'top',
				outline:(this.state.touch||this.state.mouseDown)?`${GlobalStyles.outlineWidth} ${GlobalStyles.outlineStyle} ${GlobalStyles.outlineColor}`:'none',
				//outlineOffset:GlobalStyles.outlineOffset,
				color:'inherit',
				...this.props.bStyle
			};			
			return React.createElement("td",{style:this.props.style,
				colSpan:this.props.colSpan,rowSpan:this.props.rowSpan,onClick:this.onClick},
				React.createElement("button",{style:bStyle,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd,onMouseDown:this.onMouseDown,onMouseUp:this.onMouseUp},this.props.children));
			},
	});
	const VirtualKeyboard = React.createClass({	  
		switchMode:function(e){			
			if(this.props.onChange)
				this.props.onChange({target:{value:""}});
		},
		render:function(){
			const borderSpacing = '0.2em'
			const tableStyle={
				fontSize:'1.55em',
				borderSpacing:borderSpacing,
				marginTop:'-0.2em',
				marginLeft:'auto',
				marginRight:'auto',
				...this.props.style
			};
			const tdStyle={				
				textAlign:'center',
				verticalAlign:'middle',
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle}`,				
				backgroundColor:'#eeeeee',
				height:'2.2em',
				width:'2em',
				overflow:"hidden"
			};
			const aTableStyle={
				fontSize:'1.55em',
				borderSpacing:borderSpacing,
				marginTop:'-0.2em',
				marginLeft:'auto',
				marginRight:'auto',
				lineHeight:'1.1',
				...this.props.style
			};			
			const aKeyCellStyle={
				textAlign:'center',				
				verticalAlign:'middle',
				height:'1.4em',
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle}`,
				backgroundColor:'#eeeeee',
				minWidth:'1.1em',
				overflow:"hidden"				
			};
			const aTableLastStyle={
				marginBottom:'0rem',
				position:'relative',				
				lineHeight:'1',
				fontSize:""
			};			
			const specialTdStyle={...tdStyle,...this.props.specialKeyStyle};
			const specialTdAccentStyle={...tdStyle,...this.props.specialKeyAccentStyle};
			const specialAKeyCellStyle={...aKeyCellStyle,...this.props.specialKeyStyle};
			const specialAKeyCellAccentStyle={...aKeyCellStyle,...this.props.specialKeyAccentStyle};		
			const backSpaceFillColor=this.props.alphaNumeric?(specialAKeyCellAccentStyle.color?specialAKeyCellAccentStyle.color:"#000"):(specialTdAccentStyle.color?specialTdAccentStyle.color:"#000");
			const enterFillColor=this.props.alphaNumeric?(aKeyCellStyle.color?aKeyCellStyle.color:"#000"):(tdStyle.color?tdStyle.color:"#000");
			const upFillColor=this.props.alphaNumeric?(aKeyCellStyle.color?aKeyCellStyle.color:"#000"):(tdStyle.color?tdStyle.color:"#000");
			const downFillColor=this.props.alphaNumeric?(aKeyCellStyle.color?aKeyCellStyle.color:"#000"):(tdStyle.color?tdStyle.color:"#000");
			const backSpaceSvg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24"><g fill="'+backSpaceFillColor+'" transform="scale(0.0234375 0.0234375)"><path d="M896 470v84h-604l152 154-60 60-256-256 256-256 60 60-152 154h604z" /></g></svg>';
			const enterSvg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24"><g fill="'+enterFillColor+'" transform="scale(0.0234375 0.0234375)"><path d="M810 298h86v256h-648l154 154-60 60-256-256 256-256 60 60-154 154h562v-172z" /></g></svg>';
			const upSvg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24"><g fill="'+upFillColor+'" transform="scale(0.0234375 0.0234375)"><path d="M316 658l-60-60 256-256 256 256-60 60-196-196z" /></g></svg>';
			const downSvg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24"><g fill="'+downFillColor+'" transform="scale(0.0234375 0.0234375)"><path d="M316 334l196 196 196-196 60 60-256 256-256-256z" /></g></svg>';

			const backSpaceSvgData=svgSrc(backSpaceSvg);
			const enterSvgData=svgSrc(enterSvg);
			const upSvgData=svgSrc(upSvg);
			const downSvgData=svgSrc(downSvg);
			const backSpaceEl = React.createElement("img",{src:backSpaceSvgData,style:{width:"50%",height:"100%",verticalAlign:"middle"}},null);
			const enterEl = React.createElement("img",{src:enterSvgData,style:{width:"90%",height:"100%"}},null);
			const upEl = React.createElement("img",{src:upSvgData,style:{width:"50%",height:"100%",verticalAlign:"middle"}},null);
			const downEl = React.createElement("img",{src:downSvgData,style:{width:"50%",height:"100%",verticalAlign:"middle"}},null);			 
			if(this.props.simple && !this.props.alphaNumeric)
			return React.createElement("table",{style:tableStyle,key:"1"},
				React.createElement("tbody",{key:"1"},[					  				   					  
				   React.createElement("tr",{key:"3"},[
						...[7,8,9].map(e=>React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:e,fkey:e.toString},e.toString())),						  
						React.createElement(VKTd,{rowSpan:'2',onClickValue:this.props.onClickValue,style:{...specialTdAccentStyle,height:"2rem"},key:"4",fkey:"backspace"},backSpaceEl)
				   ]),					   
				   React.createElement("tr",{key:"4"},[
						...[4,5,6].map(e=>React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:e,fkey:e.toString},e.toString()))						  				   
				   ]),
				   React.createElement("tr",{key:"5"},[
					   ...[1,2,3].map(e=>React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:e,fkey:e.toString},e.toString())),
					   React.createElement(VKTd,{rowSpan:'2',onClickValue:this.props.onClickValue,style:{...specialTdStyle,height:"90%"},key:"13",fkey:"enter"},enterEl),
				   ]),
				   React.createElement("tr",{key:"6"},[
					   React.createElement(VKTd,{colSpan:'3',onClickValue:this.props.onClickValue,style:tdStyle,key:"1",fkey:"0"},'0'),
				   ]),
			   ])
			); 
			else
			if(!this.props.alphaNumeric && !this.props.simple)
			return React.createElement("table",{style:tableStyle,key:"1"},
				React.createElement("tbody",{key:"1"},[
				   React.createElement("tr",{key:"0"},[
					   React.createElement(VKTd,{colSpan:"2",style:{...specialTdAccentStyle,height:"100%",width:"auto"},bStyle:{fontSize:""},key:"1",fkey:"Backspace"},backSpaceEl),
					   React.createElement("td",{key:"2"},''),
					   React.createElement(VKTd,{colSpan:"2",style:specialTdAccentStyle,key:"3",onClick:this.switchMode},'ABC...'),
				   ]),					   
				   React.createElement("tr",{key:"1"},[
						...["F1","F2","F3","F4","F5"].map(e=>React.createElement(VKTd,{style:specialTdStyle,key:e,fkey:e},e))						   					   
				   ]),					   
				   React.createElement("tr",{key:"2"},[
						...["F6","F7","F8","F9","F10"].map(e=>React.createElement(VKTd,{style:specialTdStyle,key:e,fkey:e},e))						   			   
				   ]),
				   React.createElement("tr",{key:"2-extras"},[
					   React.createElement(VKTd,{style:specialTdAccentStyle,colSpan:"2",key:"1",fkey:"Tab"},'Tab'),
					   ...["T",".","-"].map(e=>React.createElement(VKTd,{style:tdStyle,key:e,fkey:e},e))						   						   
				   ]),
				   React.createElement("tr",{key:"3"},[
						...[7,8,9].map(e=>React.createElement(VKTd,{style:tdStyle,key:e,fkey:e.toString()},e.toString())),						   
					   React.createElement(VKTd,{colSpan:'2',style:{...tdStyle,minWidth:'2rem',height:"100%",width:"auto"},key:"arrowup",fkey:"ArrowUp"},upEl),
				   ]),					   
				   React.createElement("tr",{key:"4"},[
						...[4,5,6].map(e=>React.createElement(VKTd,{style:tdStyle,key:e,fkey:e.toString()},e.toString())),						   
					   React.createElement(VKTd,{colSpan:'2',style:{...tdStyle,minWidth:'2rem',height:"100%",width:"auto"},key:"arrowdown",fkey:"ArrowDown"},downEl),
				   ]),
				   React.createElement("tr",{key:"5"},[
						...[1,2,3].map(e=>React.createElement(VKTd,{style:tdStyle,key:e,fkey:e.toString()},e.toString())),						   
					   React.createElement(VKTd,{colSpan:'2',rowSpan:'2',style:{...specialTdStyle,height:"100%"},key:"4",fkey:"Enter"},enterEl),
				   ]),
				   React.createElement("tr",{key:"6"},[
					   React.createElement(VKTd,{colSpan:'3',style:tdStyle,key:"1",fkey:"0"},'0'),
				   ]),
			   ])
			);
			else
			return React.createElement("div",{key:"1"},[ 
				!this.props.simple?React.createElement("table",{style:aTableStyle,key:"1"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							...["F1","F2","F3","F4","F5","F6","F7","F8","F9","F10"].map(e=>React.createElement(VKTd,{style:specialAKeyCellStyle,key:e,fkey:e},e)),																
							React.createElement(VKTd,{onClick:this.switchMode,style:specialAKeyCellAccentStyle,key:"10"},'123...'),
						])
					])
				):null,
				React.createElement("table",{style:aTableStyle,key:"2-extras"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							React.createElement(VKTd,{onClickValue:this.props.onClickValue,style:specialAKeyCellAccentStyle,colSpan:"2",key:"1",fkey:"Tab"},'Tab'),
							...[":",";","/","*","-","+",",","."].map(e=>React.createElement(VKTd,{onClickValue:this.props.onClickValue,style:aKeyCellStyle,key:e,fkey:e},e)),								
							React.createElement(VKTd,{onClickValue:this.props.onClickValue,style:{...specialAKeyCellAccentStyle,height:"100%",width:"auto",minWidth:"2em"},bStyle:{fontSize:""},key:"11",fkey:"Backspace"},backSpaceEl),
						]),
					])
				),
				React.createElement("table",{style:aTableStyle,key:"2"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							...[1,2,3,4,5,6,7,8,9,0].map(e=>React.createElement(VKTd,{onClickValue:this.props.onClickValue,style:aKeyCellStyle,key:e,fkey:e.toString()},e.toString()))								
						]),
					])
				),
				React.createElement("table",{style:aTableStyle,key:"3"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							...Array.from("QWERTYUIOP").map(e=>React.createElement(VKTd,{onClickValue:this.props.onClickValue,style:aKeyCellStyle,key:e,fkey:e},e))															
						]),
					])
				),
				React.createElement("table",{style:{...aTableStyle,position:'relative',left:'0.18rem'},key:"4"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							...Array.from("ASDFGHJKL").map(e=>React.createElement(VKTd,{onClickValue:this.props.onClickValue,style:aKeyCellStyle,key:e,fkey:e},e)),								
							React.createElement(VKTd,{onClickValue:this.props.onClickValue,style:{...specialAKeyCellStyle,minWidth:"2.5rem",height:"100%"},rowSpan:"2",key:"10",fkey:"Enter"},enterEl),
						]),
						React.createElement("tr",{key:"2"},[
							React.createElement("td",{style:{...aKeyCellStyle,backgroundColor:'transparent',border:'none'},colSpan:"9",key:"1"},[
								React.createElement("table",{style:{...aTableStyle,...aTableLastStyle},key:"1"},
									React.createElement("tbody",{key:"1"},[
										React.createElement("tr",{key:"1"},[
											...Array.from("ZXCVBNM").map(e=>React.createElement(VKTd,{onClickValue:this.props.onClickValue,style:aKeyCellStyle,key:e,fkey:e},e)),												
											React.createElement(VKTd,{onClickValue:this.props.onClickValue,style:{...aKeyCellStyle,minWidth:'2rem',height:"100%"},key:"8",fkey:"ArrowUp"},upEl),
										]),
										React.createElement("tr",{key:"2"},[
											React.createElement(VKTd,{style:{...aKeyCellStyle,visibility:"hidden"},colSpan:"1",key:"1"},''),
											React.createElement(VKTd,{onClickValue:this.props.onClickValue,style:aKeyCellStyle,colSpan:"5",key:"2",fkey:" "},'SPACE'),
											React.createElement(VKTd,{style:{...aKeyCellStyle,visibility:"hidden"},colSpan:"1",key:"3"},''),
											React.createElement(VKTd,{onClickValue:this.props.onClickValue,style:{...aKeyCellStyle,minWidth:'2rem',height:"100%"},key:"4",fkey:"ArrowDown"},downEl),
										]),
									])
								),
							]),
						]),
					])
				),

			]);			
		},
	});	

	const TableElement = ({style,children})=>React.createElement("table",{style:{
		borderCollapse:'separate',
		borderSpacing:GlobalStyles.borderSpacing,
		width:'100%',
		lineHeight:"1.1",
		minWidth:"0",
		...style
	}},children);
	const THeadElement = React.createClass({
		getInitialState:function(){
			return {dims:null,floating:false};
		},
		findTable:function(){
			if(!this.el) return;
			let parent = this.el.parentElement;
			while(parent&&parent.tagName!="TABLE") parent = parent.parentElement;
			return parent;
		},
		onScroll:function(ev){
			const tableEl  = this.findTable();
			const target = ev.target;
			if(!tableEl||target.lastElementChild != tableEl) return;			
			const floating = target.getBoundingClientRect().top > tableEl.getBoundingClientRect().top;
			if( floating&& !this.state.floating ) this.setState({floating});
			else if(!floating && this.state.floating) this.setState({floating});				
		},
		calcDims:function(){
			if(!this.el) return;
			const dim =this.el.getBoundingClientRect();
			const height = dim.height +"px";
			const width = dim.width +"px"			
			this.setState({dims:{height,width}});
		},		
		render:function(){
			const height = this.state.floating&&this.state.dims?this.state.dims.height:"";
			const width = this.state.floating&&this.state.dims?this.state.dims.width:"";
			const style={
				position:this.state.floating?"absolute":"",
				height:height,
				display:this.state.floating?"table":"",
				width:width,				
				...this.props.style
			};
			const expHeaderStyle ={
				height: height,
				display:this.state.floating?"block":"none",
			};
			
			return this.state.floating?React.createElement("div",{style:expHeaderStyle},React.createElement("thead",{style:style},this.props.children)):React.createElement("thead",{ref:ref=>this.el=ref,style:style},this.props.children);				
			
		}
	});
		
	const TBodyElement = ({style,children})=>React.createElement("tbody",{style:style},children);
	const THElement = ({style,children})=>React.createElement("th",{style:{
		borderBottom:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #b6b6b6`,
		borderLeft:'none',
		borderRight:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #b6b6b6`,
		borderTop:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #b6b6b6`,
		fontWeight:'bold',
		padding:'0.04em 0.08em 0.04em 0.08em',
		verticalAlign:'middle',
		overflow:"hidden",
		textOverflow:"ellipsis",
		...style
	}},children);
	const TDElement = ({style,children})=>React.createElement("td",{style:{
		borderLeft:'none',
		borderRight:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #b6b6b6`,
		borderTop:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #b6b6b6`,
		fontWeight:'bold',
		padding:'0.1em 0.2em',
		verticalAlign:'middle',
		fontSize:'1em',
		borderBottom:'none',
		fontWeight:'normal',
		overflow:"hidden",
		textOverflow:"ellipsis",
		...style
	}},children);
	const TRElement = React.createClass({
		getInitialState:function(){
			return {touch:false,mouseOver:false};
		},		
		onTouchStart:function(e){
			if(this.props.onClick){
				this.setState({touch:true});
			}
		},
		onTouchEnd:function(e){
			if(this.props.onClick){
				this.setState({touch:false});
			}
		},
		onMouseEnter:function(e){
			this.setState({mouseOver:true});
		},
		onMouseLeave:function(e){
			this.setState({mouseOver:false});
		},
		render:function(){
			const trStyle={
				outline:this.state.touch?`${GlobalStyles.outlineWidth} ${GlobalStyles.outlineStyle} ${GlobalStyles.outlineColor}`:'none',
				outlineOffset:GlobalStyles.outlineOffset,
				...(this.props.odd?{backgroundColor:'#fafafa'}:{backgroundColor:'#ffffff'}),
				...(this.state.mouseOver?{backgroundColor:'#eeeeee'}:null),
				...this.props.style
			};			
			return React.createElement("tr",{style:trStyle,onMouseEnter:this.onMouseEnter,onMouseLeave:this.onMouseLeave,onClick:this.props.onClick,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children);
		}	
	});
	const Interactive = React.createClass({
		getInitialState:function(){
			return {mouseOver:false};
		},
		onMouseOver:function(e){
			this.setState({mouseOver:true});
		},
		onMouseOut:function(e){
			this.setState({mouseOver:false});
		},
		render:function(){ 
			return this.props.children({
				onMouseOver:this.onMouseOver,
				onMouseOut:this.onMouseOut,
				mouseOver:this.state.mouseOver
			});
		}
	});
	const InputElementBase = React.createClass({			
		setFocus:function(){if(this.props.focus && this.inp) this.inp.focus()},
		onEnterKey:function(e){log(e);if(this.inp) this.inp.blur()},
		componentDidMount:function(){this.setFocus()},
		componentDidUpdate:function(){this.setFocus()},
		render:function(){				
			const inpContStyle={
				display:"flex",
				height:"auto",
				lineHeight:"1",
				margin:"0.124em 0em",				
				verticalAlign:"middle",
				width:"100%",
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle}`,
				borderColor:this.props.mouseOver?"black":"rgb(182, 182, 182)",
				backgroundColor:(this.props.onChange||this.props.onBlur)?"white":"#eeeeee",
				boxSizing:"border-box",
				...this.props.style
			};
			const inp2ContStyle={
				flex:"1 1 0%",
				height:"auto",
				minHeight:"100%",
				overflow:"hidden",				
			};
			const inputStyle={
				textOverflow:"ellipsis",
				margin:"0rem",
				verticalAlign:"top",
				color:"rgb(33,33,33)",
				border:"none",
				height:"100%",
				padding:"0.2172em 0.3125em 0.2172em 0.3125em",
				width:"100%",
				zIndex:"0",
				boxSizing:"border-box",
				MozAppearence:"none",
				whiteSpace:"nowrap",
				overflow:"hidden",
				fontSize:"inherit",
				textTransform:"inherit",
				backgroundColor:"inherit",
				outline:"none",
				...this.props.inputStyle
			};		
			const placeholder = this.props.placeholder?this.props.placeholder:"";
			const inputType = this.props.inputType?this.props.inputType:"input"
			const type = this.props.type?this.props.type:"text"
			const readOnly = (this.props.onChange||this.props.onBlur)?null:"true";
			const rows= this.props.rows?this.props.rows:"2";
			const actions = {onMouseOver:this.props.onMouseOver,onMouseOut:this.props.onMouseOut};			
			return React.createElement("div",{style:inpContStyle,ref:(ref)=>this.cont=ref,...actions},[
					this.props.shadowElement?this.props.shadowElement():null,
					React.createElement("div",{key:"xx",style:inp2ContStyle},[
						React.createElement(inputType,{
							key:"1",
							ref:(ref)=>this.inp=ref,
							type,rows,readOnly,placeholder,
							style:inputStyle,							
							onChange:this.props.onChange,onBlur:this.props.onBlur,onKeyDown:this.onEnter,value:this.props.value							
							},null),
						this.props.popupElement?this.props.popupElement():null
					]),
					this.props.buttonElement?this.props.buttonElement():null
				]);					
		},
	});
	const InputElement = (props) => React.createElement(Interactive,{},(actions)=>React.createElement(InputElementBase,{...props,ref:props._ref,...actions}))
	const TextAreaElement = (props) => React.createElement(Interactive,{},(actions)=>React.createElement(InputElementBase,{...props,ref:props._ref,inputType:"textarea",...actions}))

	const DropDownElement = React.createClass({
		getInitialState:function(){
			return {popupMinWidth:0};
		},
		onChange:function(e){
			if(this.props.onChange)
				this.props.onChange({target:{headers:{"X-r-action":"change"},value:e.target.value}});
		},
		onClick:function(e){
			if(this.props.onClickValue)
				this.props.onClickValue("click");
		},		
		setPopupWidth:function(){
			if(!this.inp||!this.inp.cont) return;
			const minWidth = this.inp.cont.getBoundingClientRect().width;
			if(Math.round(this.state.popupMinWidth) != Math.round(minWidth)) this.setState({popupMinWidth:minWidth});
		},
		componentDidMount:function(){
			this.setPopupWidth()		
		},
		componentDidUpdate:function(){
			this.setPopupWidth()			
		},			
		render:function(){			
			const popupStyle={
				position:"absolute",
				border: `${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} black`,
				width: this.state.popupMinWidth + "px",
				overflow: "auto",				
				maxHeight: "10em",				
				backgroundColor: "white",
				zIndex: "666",
				boxSizing:"border-box",
				overflowX:"hidden",
				marginLeft:"-0.04em",
				lineHeight:"normal",
				...this.props.popupStyle
			};
			
			const buttonImageStyle={				
				verticalAlign:"middle",
				display:"inline",
				height:"auto",
				transform:this.props.open?"rotate(180deg)":"rotate(0deg)",
				transition:"all 200ms ease",
				boxSizing:"border-box",
				...this.props.buttonImageStyle
			};
			const svg ='<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" width="16px" height="16px" viewBox="0 0 306 306" xml:space="preserve"><polygon points="270.3,58.65 153,175.95 35.7,58.65 0,94.35 153,247.35 306,94.35"/></svg>'
			const svgData=svgSrc(svg);
			const urlData = this.props.url?this.props.url:svgData;
			const buttonImage = React.createElement("img",{key:"buttonImg",src:urlData,style:buttonImageStyle},null);						
			const placeholder = this.props.placeholder?this.props.placeholder:"";
			const buttonElement = () => [React.createElement(ButtonInputElement,{key:"buttonEl",onClick:this.onClick},buttonImage)];
			const popupElement = () => [this.props.open?React.createElement("div",{key:"popup",style:popupStyle},this.props.children):null];
			
			return React.createElement(InputElement,{...this.props,_ref:(ref)=>this.inp=ref,buttonElement,popupElement,onChange:this.onChange,onBlur:this.props.onBlur});				
		}
	});
	const ButtonInputElement = React.createClass({
		getInitialState:function(){return {mouseOver:false}},
		onMouseOver:function(){this.setState({mouseOver:true})},
		onMouseOut:function(){this.setState({mouseOver:false})},
		render:function(){
			const openButtonWrapperStyle= {				
				flex:"1 1 0%",
				height:"auto",
				minHeight:"100%",
				overflow:"hidden",
				backgroundColor:"inherit",
				flex:"0 1 auto",
			};
			const openButtonStyle={
				minHeight:"",
				width:"1.5em",
				height:"100%",
				padding:"0.2em",
				lineHeight:"1",
				backgroundColor:"inherit",				
			};
			return React.createElement("div",{key:"2",style:openButtonWrapperStyle},
				React.createElement(ButtonElement,{key:"1",style:openButtonStyle,onMouseOver:this.onMouseOver,onMouseOut:this.onMouseOut,onClick:this.props.onClick},this.props.children)
		)}
	})
	const DropDownWrapperElement = ({style,children})=>React.createElement("div",{style:{
		width:"100%",				
		padding:"0.4em 0.3125em",
		boxSizing:"border-box",
		...style
	}},children);
	const LabelElement = ({style,onClick,label})=>React.createElement("label",{onClick,style:{
		color:"rgb(33,33,33)",
		cursor:onClick?"pointer":"auto",
		textTransform:"none",
		...style
	}},label?label:null);

	const FocusableElement = React.createClass({		
		onFocus:function(e){
			clearTimeout(this.timeout);						
			if(!this.focus) this.reportChange("focus");			
			this.focus=true;			
		},
		reportChange:function(state){
			if(this.props.onChange){
				this.props.onChange({target:{value:state}});				
			}
		},
		delaySend:function(){
			if(!this.focus)
				this.reportChange("blur");			
		},
		onBlur:function(e){					
			clearTimeout(this.timeout);
			this.timeout=setTimeout(this.delaySend,400);
			this.focus=false;
		},
		componentDidMount:function(){
			if(!this.el) return;
			this.el.addEventListener("focus",this.onFocus,true);
			this.el.addEventListener("blur",this.onBlur,true);
			if(this.props.onChange&&this.props.focus)
				this.el.focus();			
		},		
		componentWillUnmount:function(){
			if(!this.el) return;
			clearTimeout(this.timeout);
			this.timeout=null;
			this.el.removeEventListener("focus",this.onFocus);
			this.el.removeEventListener("blur",this.onBlur);
		},
		render:function(){
			const style={
				display:"inline-block",
				outline:"none",
				...this.props.style
			};			
			return React.createElement("div",{ref:ref=>this.el=ref,style:style,tabIndex:"0"},this.props.children);
		}
	});
	const PopupElement = React.createClass({
		getInitialState:function(){
			return {top:"",left:""};
		},
		calcPosition:function(){
			if(!this.el) return;			
			const sibling = this.el.previousElementSibling;			
			if(!sibling) return;
			const sRect = sibling.getBoundingClientRect();
			const r = this.el.getBoundingClientRect();
			let left="",top="";
			switch(this.props.position){
				case "Left":					
					left = -r.width+"px";
					top = "0px";
					break;
				case "Right":
					left = sRect.width+"px";
					top = "0px";					
					break;
				case "Top":
					left = "0px";
					top = -r.height+"px";
					break;
				case "Bottom":
					left = "0px";
					top = sRect.height+"px";
			}
			this.setState({top,left});			
		},
		componentDidMount:function(){
			if(!this.props.position) return;
			this.calcPosition();
		},
		render:function(){			
			return React.createElement("div",{ref:ref=>this.el=ref,style:{
				position:"fixed",
				zIndex:"6",
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #eee`,
				backgroundColor:"white",
				top:this.state.top,
				left:this.state.left,
				...this.props.style
			}},this.props.children);
		}		
	});
	const Checkbox = React.createClass({
		getInitialState:function(){
			return {mouseOver:false};
		},
		onMouseOver:function(){
			this.setState({mouseOver:true});
		},
		onMouseOut:function(){
			this.setState({mouseOver:false});
		},
		onClick:function(e){
			if(this.props.onChange)
				this.props.onChange({target:{value:(this.props.value?"":"checked")}});
		},
		render:function(){
			const style={
				flexGrow:"0",				
				position:"relative",
				maxWidth:"100%",
				padding:"0.4em 0.3125em",				
				flexShrink:"1",
				boxSizing:"border-box",
				lineHeight:"1",
				...this.props.altLabel?{margin:"0.124em 0em",padding:"0em"}:null,
				...this.props.style
			};
			const innerStyle={
				border:"none",
				display:"inline-block",
				lineHeight:"100%",
				margin:"0rem",				
				outline:"none",				
				whiteSpace:"nowrap",
				width:"calc(100% - 1em)",
				cursor:"pointer",
				bottom:"0rem",
				...this.props.innerStyle
			};
			const checkBoxStyle={
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle}`,
				color:"#212121",
				display:"inline-block",
				height:"1.625em",
				lineHeight:"100%",
				margin:"0em 0.02em 0em 0em",
				padding:"0rem",
				position:"relative",
				verticalAlign:"middle",
				width:"1.625em",
				boxSizing:"border-box",
				borderColor:this.state.mouseOver?"black":"rgb(182, 182, 182)",
				backgroundColor:this.props.onChange?"white":"#eeeeee",
				...this.props.altLabel?{height:"1.675em",width:"1.675em"}:null,
				...this.props.checkBoxStyle
			};
			const labelStyle={
				maxWidth:"calc(100% - 2.165em)",
				padding:"0rem 0.3125em",
				verticalAlign:"middle",
				cursor:"pointer",
				display:"inline-block",
				lineHeight:"1.3",
				overflow:"hidden",
				textOverflow:"ellipsis",
				whiteSpace:"nowrap",
				boxSizing:"border-box",
				...this.props.labelStyle
			};
			const imageStyle = {				
				bottom:"0rem",
				height:"90%",
				width:"100%",
			};			
			
			const svg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="16px" viewBox="0 0 128.411 128.411"><polygon points="127.526,15.294 45.665,78.216 0.863,42.861 0,59.255 44.479,113.117 128.411,31.666"/></svg>';
			const svgData=svgSrc(svg);
			const defaultCheckImage = this.props.value&&this.props.value.length>0?React.createElement("img",{style:imageStyle,src:svgData,key:"checkImage"},null):null
			const labelEl = this.props.label?React.createElement("label",{style:labelStyle,key:"2"},this.props.label):null;
			const checkImage = this.props.checkImage?this.props.checkImage:defaultCheckImage;
			
			return React.createElement("div",{style},
				React.createElement("span",{style:innerStyle,key:"1",onClick:this.onClick,onMouseOver:this.onMouseOver,onMouseOut:this.onMouseOut},[
					React.createElement("span",{style:checkBoxStyle,key:"1"},checkImage),
					labelEl
				])
			);
		}
	});
	
	const RadioButtonElement = (props) => {		
		const isLabeled = props.label&&props.label.length>0;			
		const innerStyle={
			...!isLabeled?{width:"auto"}:null,				
			...props.innerStyle
		};
		const checkBoxStyle={				
			height:"1em",								
			width:"1em",
			boxSizing:"border-box",				
			textAlign:"center",				
			borderRadius:"50%",
			verticalAlign:"baseline",
			...props.checkBoxStyle
		};			
		const imageStyle = {								
			height:"0.5em",
			width:"0.5em",
			display:"inline-block",
			backgroundColor:(props.value&&props.value.length>0)?"black":"transparent",
			borderRadius:"70%",
			verticalAlign:"top",
			marginTop:"0.19em",				
		};
		const checkImage = React.createElement("div",{style:imageStyle,key:"checkImage"},null);
		
		return React.createElement(Checkbox,{...props,innerStyle,checkImage,checkBoxStyle,});			
	};	
	const ConnectionState =({style,iconStyle,on})=>{
		const newStyle={			
			fontSize:"1.5em",
			lineHeight:"1",
			display:"inline-block",			
			...style			
		};
		const contStyle={
			borderRadius:"1em",
			border:`0.07em ${GlobalStyles.borderStyle} black`,
			backgroundColor:on?"green":"red",		
			display:'inline-block',
			width:"1em",
			height:"1em",
			padding:"0.25em",
			boxSizing:"border-box",
			verticalAlign:"top",
		};
		const newIconStyle={
			position:'relative',
			top:'-0.07em',
			left:'-0.05em',
			verticalAlign:"top",
			width:"0.5em",
			lineHeight:"1",			
			...iconStyle
		};			
			
		const imageSvg='<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 285.269 285.269" style="enable-background:new 0 0 285.269 285.269;" xml:space="preserve"> <path style="fill:black;" d="M272.867,198.634h-38.246c-0.333,0-0.659,0.083-0.986,0.108c-1.298-5.808-6.486-10.108-12.679-10.108 h-68.369c-7.168,0-13.318,5.589-13.318,12.757v19.243H61.553C44.154,220.634,30,206.66,30,189.262 c0-17.398,14.154-31.464,31.545-31.464l130.218,0.112c33.941,0,61.554-27.697,61.554-61.637s-27.613-61.638-61.554-61.638h-44.494 V14.67c0-7.168-5.483-13.035-12.651-13.035h-68.37c-6.193,0-11.381,4.3-12.679,10.108c-0.326-0.025-0.653-0.108-0.985-0.108H14.336 c-7.168,0-13.067,5.982-13.067,13.15v48.978c0,7.168,5.899,12.872,13.067,12.872h38.247c0.333,0,0.659-0.083,0.985-0.107 c1.298,5.808,6.486,10.107,12.679,10.107h68.37c7.168,0,12.651-5.589,12.651-12.757V64.634h44.494 c17.398,0,31.554,14.262,31.554,31.661c0,17.398-14.155,31.606-31.546,31.606l-130.218-0.04C27.612,127.862,0,155.308,0,189.248 s27.612,61.386,61.553,61.386h77.716v19.965c0,7.168,6.15,13.035,13.318,13.035h68.369c6.193,0,11.381-4.3,12.679-10.108 c0.327,0.025,0.653,0.108,0.986,0.108h38.246c7.168,0,12.401-5.982,12.401-13.15v-48.977 C285.269,204.338,280.035,198.634,272.867,198.634z M43.269,71.634h-24v-15h24V71.634z M43.269,41.634h-24v-15h24V41.634z M267.269,258.634h-24v-15h24V258.634z M267.269,228.634h-24v-15h24V228.634z"/></svg>';
		const imageSvgData = svgSrc(imageSvg);		
		return React.createElement("div",{style:newStyle},
			React.createElement("div",{key:1,style:contStyle},
				React.createElement("img",{key:"1",style:newIconStyle,src:imageSvgData},null)
			)	
		);
	};
	const FileUploadElement = React.createClass({
		getInitialState:function(){
			return {value:"",reading:false};
		},
		onClick:function(e){
			if(this.fInp)
				this.fInp.click();
		},
		onChange:function(e){
			if(this.state.reading) return;
			const reader= fileReader();
			const file = e.target.files[0];
			reader.onload=(event)=>{				
				if(this.props.onReadySendBlob){
					const blob = event.target.result;
					this.props.onReadySendBlob(this.fInp.value,blob);
				}				
				this.setState({reading:false});
			}
			reader.onprogress=()=>this.setState({reading:true});
			reader.onerror=()=>this.setState({reading:false});
			
			reader.readAsArrayBuffer(file);
			
		},						
		render:function(){			
			const style={				
				backgroundColor:(this.props.onReadySendBlob&&!this.state.reading)?"white":"#eeeeee",
				...this.props.style
			};			
			const buttonImageStyle={				
				verticalAlign:"middle",
				display:"inline",
				height:"auto",				
				boxSizing:"border-box"
			};
			const svg ='<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" width="16px" height="16px" viewBox="0 0 510 510" style="enable-background:new 0 0 510 510;" xml:space="preserve"><path d="M204,51H51C22.95,51,0,73.95,0,102v306c0,28.05,22.95,51,51,51h408c28.05,0,51-22.95,51-51V153c0-28.05-22.95-51-51-51 H255L204,51z"/></svg>';
			const svgData=svgSrc(svg);
			const urlData = this.props.url?this.props.url:svgData;
			const buttonImage = React.createElement("img",{key:"buttonImg",src:urlData,style:buttonImageStyle},null);
			const placeholder = this.props.placeholder?this.props.placeholder:"";
			const shadowElement = [React.createElement("input",{key:"0",ref:(ref)=>this.fInp=ref,onChange:this.onChange,type:"file",style:{visibility:"hidden",position:"absolute",height:"1px",width:"1px"}},null)];
			const buttonElement = [React.createElement(ButtonInputElement,{key:"2"},buttonImage)];
			
			return React.createElement(InputElement,{...this.props,style,shadowElement,buttonElement,onChange:()=>{},onClick:()=>{}});
		}
	}); 
	
	const ChangePassword = prop => {
        const [attributesA,attributesB] = pairOfInputAttributes(prop,{"X-r-auth":"change"})
		const defButtonStyle = {alignSelf:"flex-end",marginBottom:"0.524em"}
		const disabledButtonStyle = {backgroundColor:"lightGrey",...defButtonStyle}
		const buttonStyle = attributesA.value && attributesA.value === attributesB.value?{backgroundColor:"#c0ced8",...defButtonStyle}:disabledButtonStyle
		const buttonOverStyle = attributesA.value && attributesA.value === attributesB.value?{backgroundColor:"#d4e2ec",...defButtonStyle}:disabledButtonStyle		
		const onClick = attributesA.value && attributesA.value === attributesB.value? prop.onBlur:()=>{}
        const passwordCaption = prop.passwordCaption?prop.passwordCaption:"New Password";
		const passwordRepeatCaption = prop.passwordRepeatCaption?prop.passwordRepeatCaption:"Again";
		const buttonCaption = prop.buttonCaption?prop.buttonCaption:"Submit";
        return React.createElement("form",{onSubmit:(e)=>e.preventDefault()},
			React.createElement("div",{key:"1",style:{display:"flex"}},[
				React.createElement(DropDownWrapperElement,{key:"1",style:{flex:"1 1 0%"}},
					React.createElement(LabelElement,{label:passwordCaption},null),
					React.createElement(InputElement,{...attributesA,focus:prop.focus,type:"password"},null)			
				),
				React.createElement(DropDownWrapperElement,{key:"2",style:{flex:"1 1 0%"}},
					React.createElement(LabelElement,{label:passwordRepeatCaption},null),
					React.createElement(InputElement,{...attributesB,focus:false,type:"password"},null)			
				),            
				React.createElement(ButtonElement, {key:"3",onClick, style:buttonStyle,overStyle:buttonOverStyle}, buttonCaption)
			])
		)
    }
    const SignIn = prop => {
        const [attributesA,attributesB] = pairOfInputAttributes(prop,{"X-r-auth":"check"})
		const buttonStyle = {backgroundColor:"#c0ced8",...prop.buttonStyle}
		const buttonOverStyle = {backgroundColor:"#d4e2ec",...prop.buttonOverStyle}
		const usernameCaption = prop.usernameCaption?prop.usernameCaption:"Username";
		const passwordCaption = prop.passwordCaption?prop.passwordCaption:"Password";
		const buttonCaption = prop.buttonCaption?prop.buttonCaption:"LOGIN";		
        return React.createElement("div",{style:{margin:"1em 0em",...prop.style}},
			React.createElement("form",{key:"form",onSubmit:e=>e.preventDefault()},[
				React.createElement(DropDownWrapperElement,{key:"1"},
					React.createElement(LabelElement,{label:usernameCaption},null),
					React.createElement(InputElement,{...attributesA,focus:prop.focus},null)			
				),
				React.createElement(DropDownWrapperElement,{key:"2"},
					React.createElement(LabelElement,{label:passwordCaption},null),
					React.createElement(InputElement,{...attributesB,focus:false,type:"password"},null)			
				),
				React.createElement("div",{key:"3",style:{textAlign:"right",paddingRight:"0.3125em"}},
					React.createElement(ButtonElement,{onClick:prop.onBlur,style:buttonStyle,overStyle:buttonOverStyle},buttonCaption)
				)
			])
		)
	}	
	
	const CalenderCell=React.createClass({
		getInitialState:function(){
			return {mouseOver:false};
		},
		onMouseOver:function(){
			this.setState({mouseOver:true});
		},
		onMouseOut:function(){
			this.setState({mouseOver:false});
		},
		onClick:function(){
			const monthAdj = this.props.m=="p"?"-1":this.props.m=="n"?"1":"0"
			const value = "month:"+monthAdj+";day:"+this.props.curday.toString();
			if(this.props.onClickValue)
				this.props.onClickValue("change",value);
		},
		render:function(){											
			const isSel = this.props.curday == this.props.curSel;
			const calDayCellStyle ={
				width:"12.46201429%",
				margin:"0 0 0 2.12765%",											
				textAlign:"center",
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle}`,
				borderColor:!this.props.m?"#d4e2ec":"transparent",
				backgroundColor:isSel?"transparent":(this.state.mouseOver?"#c0ced8":"transparent")				
			};
			const cellStyle={
				cursor:"pointer",
				padding:"0.3125em 0",
				backgroundColor:isSel?"#ff3d00":"transparent"
			};
			const aCellStyle={
				color:isSel?"white":"#212121",											
				textAlign:"center",
				textDecoration:"none",				
			};
			
			return React.createElement("div",{onClick:this.onClick,style:calDayCellStyle,onMouseOver:this.onMouseOver,onMouseOut:this.onMouseOut},
				React.createElement("div",{style:cellStyle},
					React.createElement("a",{style:aCellStyle},this.props.curday)
				)
			);
		}
	});
	const CalendarYM = React.createClass({
		getInitialState:function(){
			return {mouseOver:false};
		},
		onMouseOver:function(){
			this.setState({mouseOver:true});
		},
		onMouseOut:function(){
			this.setState({mouseOver:false});
		},
		render:function(){
			const style={
				width:"12.46201429%",
				margin:"0 0 0 2.12765%",						
				textAlign:"center",
				backgroundColor:this.state.mouseOver?"#c0ced8":"transparent",
				color:this.state.mouseOver?"#212112":"white",
				...this.props.style
			};
			return React.createElement("div",{onClick:this.props.onClick,style,onMouseOver:this.onMouseOver,onMouseOut:this.onMouseOut},this.props.children);
		}
	});
	const CalenderSetNow = React.createClass({
		getInitialState:function(){
			return {mouseOver:false};
		},
		onMouseOver:function(){
			this.setState({mouseOver:true});
		},
		onMouseOut:function(){
			this.setState({mouseOver:false});
		},
		render:function(){			
			const style={
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #d4e2ec`,
				color:"#212121",
				cursor:"pointer",
				display:"inline-block",
				padding:".3125em 2.3125em",
				backgroundColor:this.state.mouseOver?"#c0ced8":"transparent",
				...this.props.style
			};
			return React.createElement("div",{style,onClick:this.props.onClick,onMouseOver:this.onMouseOver,onMouseOut:this.onMouseOut},this.props.children);			
		}
	});
	const CalenderTimeButton = React.createClass({
		getInitialState:function(){
			return {mouseOver:false};
		},
		onMouseOver:function(){
			this.setState({mouseOver:true});
		},
		onMouseOut:function(){
			this.setState({mouseOver:false});
		},
		render:function(){
			const style = {
				backgroundColor:this.state.mouseOver?"#c0ced8":"transparent",
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #d4e2ec`,
				cursor:"pointer",
				padding:"0.25em 0",
				width:"2em",
				fontSize:"1em",
				outline:"none"
			};			
			return React.createElement("button",{style,onClick:this.props.onClick,onMouseOver:this.onMouseOver,onMouseOut:this.onMouseOut},this.props.children);			
		}
	});
	const DateTimePickerYMSel = function({month,year,onClickValue}){
		const monthNames=["January","February","March","April","May","June","July","August","September","October","November","December"];
		const headerStyle={
			width:"100%",
			backgroundColor:"#005a7a",
			color:"white",
			margin:"0px",
			display:"flex"
		};
		const aItem = function(c,s){
			const aStyle={
				cursor:"pointer",
				display:"block",
				textDecoration:"none",						
				padding:"0.3125em",
				...s
			};
			return React.createElement("a",{style:aStyle},c);
		};				
		const ymStyle = {
			width:"41.64134286%",
			whiteSpace:"nowrap"
		};
		const changeYear = (adj)=>()=>{
			if(onClickValue) onClickValue("change","year:"+adj.toString())
		}
		const changeMonth = (adj)=>()=>{
			if(onClickValue) onClickValue("change","month:"+adj.toString())
		}
		
		const selMonth  = parseInt(month)?parseInt(month):0;
		return React.createElement("div",{style:headerStyle},[
			React.createElement(CalendarYM,{onClick:changeYear(-1),key:"1",style:{margin:"0px"}},aItem("-")),
			React.createElement(CalendarYM,{onClick:changeMonth(-1),key:"2"},aItem("〈")),
			React.createElement(CalendarYM,{key:"3",style:ymStyle},aItem(monthNames[selMonth]+" "+year,{padding:"0.325em 0 0.325em 0",cursor:"default"})),
			React.createElement(CalendarYM,{onClick:changeMonth(1),key:"4"},aItem("〉")),
			React.createElement(CalendarYM,{onClick:changeYear(1),key:"5"},aItem("+"))					
		]);
		
	};
	const DateTimePickerDaySel = ({month,year,curSel,onClickValue})=>{
		const dayNames  = ["Mon","Tue","Wed","Thu","Fri","Sat","Sun"];
		const weekDaysStyle={
			margin:"0 0 .3125em",
			width:"100%",
			display:"flex",
			fontSize:"0.75em"
		};
		const dayOfWeekStyle={
			width:"10.5%",
			margin:"0 0 0 2.12765%",
			padding:"0.3125em 0 0.3125em 0",					
			textAlign:"center"
		};
		const dayStyle={
			color:"#212121"
		};
		const cal_makeDaysArr=function(month, year) {
			function cal_daysInMonth(month, year) {
				return 32 - new Date(year, month, 32).getDate();
			}
			const daysArray = [];
			let dayOfWeek = new Date(year, month, 1).getDay();
			const prevMDays = cal_daysInMonth(month ? month-1 : 11, year);
			const currMDays = cal_daysInMonth(month, year);
			
			if (!dayOfWeek) dayOfWeek = 7;	// First week is from previous month
			
			for(let i = 1; i < dayOfWeek; i++)
				daysArray.push(prevMDays - dayOfWeek + i + 1);

			for(let i = 1; i <= currMDays; i++)
				daysArray.push(i);

			for(let i = 1; i <= 42-dayOfWeek-currMDays+1; i++)
				daysArray.push(i);	
				
			return daysArray;
		}
		const rowsOfDays=function(dayArray,cDay){
			const weeknum = dayArray.length/7;
			let daynum  = 0;
			const cal = cDay;			
			let firstDayOfMonthTriger = true;
			let firstDayOfMonth = new Date(cal.year, cal.month,1).getDay();
			firstDayOfMonth = (firstDayOfMonth==0) ? firstDayOfMonth=6 : firstDayOfMonth-1;			
			const dayInMonth = new Date(cal.year, (cal.month+1), 0).getDate();

			const rows=[];
			let w;
			for(w = 0;w < weeknum;w++){
				rows.push(React.createElement("tr",{key:""+w},
				(()=>{
					let weekNumber;
					const curday = dayArray[daynum];
					if(daynum >= dayInMonth + firstDayOfMonth){
						weekNumber = new Date(cal.year, (cal.month+1), curday, 0, 0, 0, 0).getISOWeek();
					}
					else if(daynum < firstDayOfMonth){
						weekNumber = new Date(cal.year, (cal.month-1), curday, 0, 0, 0, 0).getISOWeek();
					} else {
						weekNumber = new Date(cal.year, cal.month, curday, 0, 0, 0, 0).getISOWeek();
					}
					const weekNumStyle={
						borderRight:"0.04em solid #212121",
						padding:"0em 0em 0,3125em",
						width:"10.5%",
						verticalAlign:"top"
					};
					const weekNumCellStyle={							
						textAlign:"center",
						padding:"0.3125em",
						margin:"0 0 0 2.12765%"							
					};
					const calRowStyle={
						padding:"0em 0em .3125em .3125em",
						margin:"0em",
						width:"100%",						
					};
					return [
						React.createElement("td",{key:w+"1",style:weekNumStyle},
							React.createElement("div",{style:weekNumCellStyle},
								React.createElement("div",{},weekNumber)
							)
						),
						React.createElement("td",{key:w+"2",style:calRowStyle},
							React.createElement("div",{style:{...calRowStyle,padding:"0px",display:"flex"}},
							(()=>{
								const cells=[];									
								for(let d = 0; d < 7; d++) {
									const curday = dayArray[daynum];
									if (daynum < 7 && curday > 20)
										cells.push(React.createElement(CalenderCell,{key:d,curday,m:"p",onClickValue}));
									else if (daynum > 27 && curday < 20)
										cells.push(React.createElement(CalenderCell,{key:d,curday,m:"n",onClickValue}));
									else
										cells.push(React.createElement(CalenderCell,{key:d,curday,curSel,onClickValue}));
									daynum++;
								}
								return cells;
							})())
						)	
					];
				})()	
				));					
			}		
			const tableStyle={
				width:"100%",
				color:"#212121",
				borderSpacing:"0em"
			}
			return React.createElement("table",{key:"rowsOfDays",style:tableStyle},
				React.createElement("tbody",{},rows)
			);
		};
		return React.createElement("div",{},[
			React.createElement("div",{key:"daysRow",style:weekDaysStyle},[
				React.createElement("div",{key:"1",style:dayOfWeekStyle},React.createElement("div",{}," ")),
				dayNames.map((day,i)=>
					React.createElement("div",{key:"d"+i,style:dayOfWeekStyle},
						React.createElement("div",{style:dayStyle},day)
					)
				)
			]),
			rowsOfDays(cal_makeDaysArr(month,year),{month,year})
		]);
	}
	const DateTimePickerTSelWrapper = ({children})=>{
		return React.createElement("table",{key:"todayTimeSelWrapper",style:{width:"100%"}},
			React.createElement("tbody",{},
				React.createElement("tr",{},
					React.createElement("td",{colSpan:"8"},
						React.createElement("div",{style:{textAlign:"center"}},children)
					)
				)
			)
		);
	};
	const DateTimePickerTimeSel = ({hours,mins,onClickValue})=>{		
		const adjHours = hours.length==1?'0'+hours:hours;
		const adjMins = mins.length==1?'0'+mins:mins;
		const tableStyle = {
			width:"100%",
			marginBottom:"1.5em",
			marginTop:"1em",
			borderCollapse:"collapse",
			color:"#212121"
		};
		const changeHour = (adj)=>()=>{
			if(onClickValue) onClickValue("change","hour:"+adj.toString());
		}
		const changeMin = (adj)=>()=>{
			if(onClickValue) onClickValue("change","min:"+adj.toString());
		}
		return React.createElement("table",{key:"timeSelect",style:tableStyle},
			React.createElement("tbody",{},[
				React.createElement("tr",{key:1},[
					React.createElement("td",{key:1,style:{textAlign:"right"}},
						React.createElement(CalenderTimeButton,{onClick:changeHour(1)},"+")
					),
					React.createElement("td",{key:2,style:{textAlign:"center"}}),
					React.createElement("td",{key:3,style:{textAlign:"left"}},
						React.createElement(CalenderTimeButton,{onClick:changeMin(1)},"+")
					)							
				]),
				React.createElement("tr",{key:2},[
					React.createElement("td",{key:1,style:{textAlign:"right"}},adjHours),
					React.createElement("td",{key:2,style:{textAlign:"center"}},":"),
					React.createElement("td",{key:3,style:{textAlign:"left"}},adjMins),
				]),
				React.createElement("tr",{key:3},[
					React.createElement("td",{key:1,style:{textAlign:"right"}},
						React.createElement(CalenderTimeButton,{onClick:changeHour(-1)},"-")
					),
					React.createElement("td",{key:2,style:{textAlign:"center"}}),
					React.createElement("td",{key:3,style:{textAlign:"left"}},
						React.createElement(CalenderTimeButton,{onClick:changeMin(-1)},"-")
					)	
				])
			])
		);				
	};
	const DateTimePickerNowSel = ({onClick,value})=>React.createElement(CalenderSetNow,{key:"setNow",onClick:onClick},value);
	const DateTimePicker = (props) => {		
		const calWrapper=function(children){
			const wrapperStyle={
				padding:".3125em",
				backgroundColor:"white",
				minWidth:"15.75em",
				boxShadow:GlobalStyles.boxShadow
			};
			const gridStyle={
				margin:"0px",
				padding:"0px"
			};
			return React.createElement("div",{style:wrapperStyle},
				React.createElement("div",{style:gridStyle},
					children
				));
		};	
		const popupStyle={
			width:"auto",
			maxHeight:"auto",
			...props.popupStyle
		};			
		const buttonImageStyle={				
			transform:"none",
			...props.buttonImageStyle
		};			
		const svg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" x="0" y="0" viewBox="0 0 512 512" style="enable-background:new 0 0 512 512;" xml:space="preserve">'
			  +'<path style="fill:#FFFFFF;" d="M481.082,123.718V72.825c0-11.757-9.531-21.287-21.287-21.287H36         c-11.756,0-21.287,9.53-21.287,21.287v50.893L481.082,123.718L481.082,123.718z"/>'
			  +'<g><path d="M481.082,138.431H14.713C6.587,138.431,0,131.843,0,123.718V72.825c0-19.85,16.151-36,36-36h423.793   c19.851,0,36,16.151,36,36v50.894C495.795,131.844,489.208,138.431,481.082,138.431z M29.426,109.005h436.942v-36.18   c0-3.625-2.949-6.574-6.574-6.574H36c-3.625,0-6.574,2.949-6.574,6.574V109.005z"/>'
			  +'<path d="M144.238,282.415H74.93c-8.126,0-14.713-6.589-14.713-14.713v-61.765   c0-8.125,6.587-14.713,14.713-14.713h69.308c8.126,0,14.713,6.589,14.713,14.713c0,8.125-6.587,14.713-14.713,14.713H89.643v32.338   h54.595c8.126,0,14.713,6.589,14.713,14.713S152.364,282.415,144.238,282.415z"/></g>'
			  +'<g><path d="M282.552,282.415h-69.308c-8.126,0-14.713-6.589-14.713-14.713v-61.765   c0-8.125,6.587-14.713,14.713-14.713h69.308c8.126,0,14.713,6.589,14.713,14.713v61.765   C297.265,275.826,290.678,282.415,282.552,282.415z M227.957,252.988h39.882V220.65h-39.882V252.988z"/>'
			  +'<path d="M144.238,406.06H74.93c-8.126,0-14.713-6.589-14.713-14.713v-61.765   c0-8.125,6.587-14.713,14.713-14.713h69.308c8.126,0,14.713,6.589,14.713,14.713s-6.587,14.713-14.713,14.713H89.643v32.338h54.595   c8.126,0,14.713,6.589,14.713,14.713S152.364,406.06,144.238,406.06z"/></g>'
			  +'<path d="M282.552,406.06h-69.308c-8.126,0-14.713-6.589-14.713-14.713v-61.765  c0-8.125,6.587-14.713,14.713-14.713h69.308c8.126,0,14.713,6.589,14.713,14.713v61.765  C297.265,399.471,290.678,406.06,282.552,406.06z M227.957,376.633h39.882v-32.338h-39.882V376.633z"/>'
			  +'<g><path d="M420.864,282.415h-69.308c-8.126,0-14.713-6.589-14.713-14.713v-61.765   c0-8.125,6.587-14.713,14.713-14.713h69.308c8.126,0,14.713,6.589,14.713,14.713v61.765   C435.577,275.826,428.99,282.415,420.864,282.415z M366.269,252.988h39.882V220.65h-39.882V252.988L366.269,252.988z"/>'
			  +'<path d="M99.532,92.878c-8.126,0-14.713-6.589-14.713-14.713V22.06c0-8.125,6.587-14.713,14.713-14.713   s14.713,6.589,14.713,14.713v56.106C114.245,86.291,107.658,92.878,99.532,92.878z"/>'
			  +'<path d="M247.897,92.878c-8.126,0-14.713-6.589-14.713-14.713V22.06c0-8.125,6.587-14.713,14.713-14.713   s14.713,6.589,14.713,14.713v56.106C262.61,86.291,256.023,92.878,247.897,92.878z"/>'
			  +'<path d="M396.263,92.878c-8.126,0-14.713-6.589-14.713-14.713V22.06c0-8.125,6.587-14.713,14.713-14.713   s14.713,6.589,14.713,14.713v56.106C410.976,86.291,404.389,92.878,396.263,92.878z"/>'
			  +'<path d="M389.88,504.653c-67.338,0-122.12-54.782-122.12-122.12s54.782-122.12,122.12-122.12   c36.752,0,71.2,16.321,94.512,44.78c5.15,6.285,4.229,15.556-2.058,20.706c-6.285,5.148-15.556,4.229-20.706-2.058   c-17.7-21.608-43.851-33.999-71.747-33.999c-51.111,0-92.693,41.582-92.693,92.693s41.582,92.693,92.693,92.693   s92.693-41.582,92.693-92.693c0-8.125,6.587-14.713,14.713-14.713c8.126,0,14.713,6.589,14.713,14.713   C512,449.87,457.218,504.653,389.88,504.653z"/>'
			  +'<path d="M228.475,490.606H36c-19.85,0-36-16.151-36-36V72.825c0-19.85,16.151-36,36-36h423.793   c19.851,0,36,16.151,36,36v164.701c0,8.125-6.587,14.713-14.713,14.713c-8.126,0-14.713-6.589-14.713-14.713V72.825   c0-3.625-2.949-6.574-6.574-6.574H36c-3.625,0-6.574,2.949-6.574,6.574v381.781c0,3.625,2.949,6.574,6.574,6.574h192.474   c8.126,0,14.713,6.589,14.713,14.713C243.187,484.018,236.601,490.606,228.475,490.606z"/></g>'
			  +'<polyline style="fill:#FFFFFF;" points="429.606,382.533 389.88,382.533 389.88,342.808 "/>'
			  +'<path d="M429.606,397.247H389.88c-8.126,0-14.713-6.589-14.713-14.713v-39.726  c0-8.125,6.587-14.713,14.713-14.713s14.713,6.589,14.713,14.713v25.012h25.012c8.126,0,14.713,6.589,14.713,14.713  S437.732,397.247,429.606,397.247z"/>'
			  +'</svg>';
		const svgData=svgSrc(svg);	  
		const urlData = props.url?props.url:svgData;			
		return React.createElement(DropDownElement,{...props,popupStyle,buttonImageStyle,url:urlData,children:calWrapper(props.children)});			
	}
	
	Date.prototype.getISOWeek = function(utc){
		var y = utc ? this.getUTCFullYear(): this.getFullYear();
		var m = utc ? this.getUTCMonth() + 1: this.getMonth() + 1;
		var d = utc ? this.getUTCDate() : this.getDate();
		var w;
		// If month jan. or feb.
		if (m < 3) {
		  var a = y - 1;
		  var b = (a / 4 | 0) - (a / 100 | 0) + (a / 400 | 0);
		  var c = ( (a - 1) / 4 | 0) - ( (a - 1) / 100 | 0) + ( (a - 1) / 400 | 0);
		  var s = b - c;
		  var e = 0;
		  var f = d - 1 + 31 * (m - 1);
		}
		// If month mar. through dec.
		else {
		  var a = y;
		  var b = (a / 4 | 0) - ( a / 100 | 0) + (a / 400 | 0);
		  var c = ( (a - 1) / 4 | 0) - ( (a - 1) / 100 | 0) + ( (a - 1) / 400 | 0);
		  var s = b - c;
		  var e = s + 1;
		  var f = d + ( (153 * (m - 3) + 2) / 5 | 0) + 58 + s;
		}
		var g = (a + b) % 7;
		// ISO Weekday (0 is monday, 1 is tuesday etc.)
		var d = (f + g - e) % 7;
		var n = f + 3 - d;
		if (n < 0)
		  w = 53 - ( (g - s) / 5 | 0);
		else if (n > 364 + s)
		  w = 1;
		else
		  w = (n / 7 | 0) + 1;
		return w;
	};
	const sendVal = ctx =>(action,value) =>{sender.send(ctx,({headers:{"X-r-action":action},value}));}
	const sendBlob = ctx => (name,value) => {sender.send(ctx,({headers:{"X-r-action":name},value}));}	
	const onClickValue=({sendVal});
	
	const onReadySendBlob=({sendBlob});
	const transforms= {
		tp:{
            DocElement,FlexContainer,FlexElement,ButtonElement, TabSet, GrContainer, FlexGroup, VirtualKeyboard,
            InputElement,
			DropDownElement,DropDownWrapperElement,
			LabelElement,Chip,FocusableElement,PopupElement,Checkbox,
            RadioButtonElement,FileUploadElement,TextAreaElement,
			DateTimePicker,DateTimePickerYMSel,DateTimePickerDaySel,DateTimePickerTSelWrapper,DateTimePickerTimeSel,DateTimePickerNowSel,
            MenuBarElement,MenuDropdownElement,FolderMenuElement,ExecutableMenuElement,
            TableElement,THeadElement,TBodyElement,THElement,TRElement,TDElement,
            ConnectionState,
			SignIn,ChangePassword			
		},
		onClickValue,		
		onReadySendBlob
	};
	return ({transforms});
}
