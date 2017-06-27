"use strict";
import React from 'react'
import {pairOfInputAttributes}  from "../main/vdom-util"
import Helmet from "react-helmet"
import Errors from "../extra/errors"
/*
todo:
extract mouse/touch to components https://facebook.github.io/react/docs/jsx-in-depth.html 'Functions as Children'
jsx?
*/

export default function MetroUi({log,sender,press,svgSrc,fileReader,documentManager,focusModule,eventManager,dragDropModule,windowManager,miscReact}){
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
		const update = (newStyles) => styles = {...styles,...newStyles}
		return {...styles,update};
	})()
	const checkActivateCalls=(()=>{
		const callbacks=[]
		const add = (c) => callbacks.push(c)
		const remove = (c) => {
			const index = callbacks.indexOf(c)
			callbacks.splice(index,1)
		}
		const check = () => callbacks.forEach(c=>c())
		return {add,remove,check}
	})();
	const {isReactRoot,getReactRoot} = miscReact
	const {setTimeout,clearTimeout,getPageYOffset,addEventListener,removeEventListener,getWindowRect,getComputedStyle} = windowManager
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
				fontFamily:'inherit',
				outline:this.state.touch?`${GlobalStyles.outlineWidth} ${GlobalStyles.outlineStyle} ${GlobalStyles.outlineColor}`:'none',
				outlineOffset:GlobalStyles.outlineOffset,
				backgroundColor:this.state.mouseOver?"#ffffff":"#eeeeee",
				...this.props.style,
				...(this.state.mouseOver?this.props.overStyle:null)
			}	
			return React.createElement("button",{style,onMouseOver:this.mouseOver,onMouseOut:this.mouseOut,onClick:this.onClick,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children);
		}
	});
	const uiElements = []
	const errors = Errors({log,uiElements,documentManager})
	const $ = React.createElement
	const ErrorElement = React.createClass({
		getInitialState:function(){return {show:false,data:null}},
		callback:function(data){
			log(`hehe ${data}`)
			this.setState({show:true,data})
		},
		componentDidMount:function(){
			this.binding = errors.reg(this.callback)
		},
		onClick:function(e){
			log(`click`)
			this.setState({show:false,data:null})
			if(this.props.onClick) this.props.onClick(e)
		},
		componentWillUnmount:function(){
			if(this.binding) this.binding.unreg()
		},
		render:function(){
			if(this.state.show||this.props.data){
				const fillColor = "black"
				const closeSvg = `
				<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 348.333 348.334" style="enable-background:new 0 0 348.333 348.334;" xml:space="preserve" fill="${fillColor}">
					<path d="M336.559,68.611L231.016,174.165l105.543,105.549c15.699,15.705,15.699,41.145,0,56.85 c-7.844,7.844-18.128,11.769-28.407,11.769c-10.296,0-20.581-3.919-28.419-11.769L174.167,231.003L68.609,336.563 c-7.843,7.844-18.128,11.769-28.416,11.769c-10.285,0-20.563-3.919-28.413-11.769c-15.699-15.698-15.699-41.139,0-56.85   l105.54-105.549L11.774,68.611c-15.699-15.699-15.699-41.145,0-56.844c15.696-15.687,41.127-15.687,56.829,0l105.563,105.554   L279.721,11.767c15.705-15.687,41.139-15.687,56.832,0C352.258,27.466,352.258,52.912,336.559,68.611z"/>
				</svg>`;
				const noteSvg = `
				<svg fill="red" version="1.1" xmlns="http://www.w3.org/2000/svg" x="0" y="0" viewBox="0 0 490 490" style="enable-background:new 0 0 490 490;" xml:space="preserve">  
					<path d="M244.5,0C109.3,0,0,109.3,0,244.5S109.3,489,244.5,489S489,379.7,489,244.5S379.7,0,244.5,0z M244.5,448.4
							 c-112.4,0-203.9-91.5-203.9-203.9S132.1,40.6,244.5,40.6s203.9,91.5,203.9,203.9S356.9,448.4,244.5,448.4z" />
					<path d="M354.8,134.2c-8.3-8.3-20.8-8.3-29.1,0l-81.2,81.2l-81.1-81.1c-8.3-8.3-20.8-8.3-29.1,0s-8.3,20.8,0,29.1l81.1,81.1
							 l-81.1,81.1c-8.3,8.3-8.6,21.1,0,29.1c6.5,6,18.8,10.4,29.1,0l81.1-81.1l81.1,81.1c12.4,11.7,25,4.2,29.1,0
							 c8.3-8.3,8.3-20.8,0-29.1l-81.1-81.1l81.1-81.1C363.1,155,363.1,142.5,354.8,134.2z" />
				</svg>`;
				const closeSvgData = svgSrc(closeSvg)
				const noteSvgData = svgSrc(noteSvg)
				const closeImg = $("img",{src:closeSvgData,style:{width:"1.5em",display:"inherit",height:"0.7em"}})
				const noteImg = $("img",{src:noteSvgData,style:{width:"1.5em",display:"inherit"}})
				const data = this.props.data?this.props.data:this.state.data
				const errorEl = $("div",{style:{backgroundColor:"white",padding:"0em 1.25em",borderTop:"0.1em solid #1976d2",borderBottom:"0.1em solid #1976d2"}},
					$("div",{style:{display:"flex",height:"2em",margin:"0.2em"}},[
						$("div",{key:"msg",style:{display:"flex",flex:"1 1 auto",minWidth:"0"}},[
							$("div",{key:"icon",style:{alignSelf:"center"}},noteImg),
							$("div",{key:"msg",style:{alignSelf:"center",color:"red",flex:"0 1 auto",margin:"0em 0.5em",overflow:"hidden",textOverflow:"ellipsis",whiteSpace:"nowrap"}},data)						
						]),
						$(ButtonElement,{key:"but1",onClick:this.onClick,style:{margin:"5mm",flex:"0 0 auto"}},"OK"),
						$(ButtonElement,{key:"but2",onClick:this.onClick,style:{margin:"5mm",flex:"0 0 auto"}},closeImg)
					])
				)			
				return errorEl
			}	
			else 
				return null
		}
	})
	uiElements.push({ErrorElement})
	const MenuBarElement=React.createClass({
		getInitialState:function(){
			return {fixedHeight:"",scrolled:false}
		},
		process:function(){
			if(!this.el) return;
			const height = this.el.getBoundingClientRect().height + "px";			
			if(height !== this.state.fixedHeight) this.setState({fixedHeight:height});			
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
				height:this.state.fixedHeight,				
			}
			const menuStyle = {
				position:"fixed",
				width:"100%",
				zIndex:"6662",
				top:"0rem",
				boxShadow:this.state.scrolled?GlobalStyles.boxShadow:"",
				...this.props.style				
			}
			const barStyle = {
				display:'flex',
				flexWrap:'nowrap',
				justifyContent:'flex-start',
				backgroundColor:'#2196f3',
				verticalAlign:'middle',				
				width:"100%"				
			}
			return React.createElement("div",{style:style},
				React.createElement("div",{style:menuStyle,className:"menuBar",ref:ref=>this.el=ref},[
					React.createElement("div",{key:"menuBar",style:barStyle,className:"menuBar"},this.props.children),
					React.createElement(ErrorElement,{key:"errors",onClick:this.process})
				])
			)
		}		
	});
	const getParentNode = function(childNode,className){
		let parentNode = childNode.parentNode;
		while(parentNode!=null&&parentNode!=undefined){
			if(parentNode.classList.contains(className)) break;
			parentNode = parentNode.parentNode;
		}
		return parentNode;
	}
	const MenuDropdownElement = React.createClass({
		getInitialState:function(){
			return {maxHeight:"",rightOffset:0};
		},
		calcMaxHeight:function(){
			if(!this.el) return;			
			const elTop = this.el.getBoundingClientRect().top;
			const innerHeight = getWindowRect().height;
			if(this.props.isOpen&&parseFloat(this.state.maxHeight)!=innerHeight - elTop)						
				this.setState({maxHeight:innerHeight - elTop + "px"});				
		},
		componentDidMount:function(){
			if(!this.el) return
			const menuRoot = getParentNode(this.el,"menuBar");
			const maxRight = menuRoot.getBoundingClientRect().right
			const elRight = this.el.getBoundingClientRect().right
			if(elRight>maxRight) this.setState({rightOffset:elRight - maxRight})
		},
		render:function(){
			return React.createElement("div",{
				ref:ref=>this.el=ref,
				style: {
					position:'absolute',					
					minWidth:'7em',
					marginLeft:(-this.state.rightOffset) + "px",
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
	const DocElement=(props) =>{
		const fontFaceStyle = `
			@font-face {
				font-family: "Open Sans";
				font-style: normal;
				font-weight: 400;
				src: local("Segoe UI"), local("Open Sans"), local("OpenSans"), url(https://themes.googleusercontent.com/static/fonts/opensans/v8/K88pR3goAWT7BTt32Z01mz8E0i7KZn-EPnyo3HZu7kw.woff) format('woff');
			}`;
		const fontSize = props.style.fontSize?props.style.fontSize:"";
		const padding = props.style.padding?props.style.padding:"";
		const htmlStyle = `
			html {
				font-size: ${fontSize};
				font-family:"Open Sans";
				padding: ${padding};
			}`;
		const bodyStyle = `
			body {
				margin:0em;
			}`;
		return React.createElement(Helmet,{},
			React.createElement("style",{},fontFaceStyle+htmlStyle+bodyStyle)
		)
	}	
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
		getCurrentBpPixels:function(){
			const bpPixels = parseInt(this.props.bp)
			if(!this.emEl) return bpPixels*13
			return bpPixels * this.emEl.getBoundingClientRect().height;
		},
		shouldRotate:function(){			
			const elWidth = this.groupEl.getBoundingClientRect().width
			const bpWidth = this.getCurrentBpPixels()
			if(elWidth<bpWidth && this.state.rotated){
				this.setState({rotated:false});
				return true;
			}
			else if(elWidth> bpWidth && !this.state.rotated){
				this.setState({rotated:true});
				return true;
			}	
			return false;
		},
		recalc:function(){
			if(!this.captionEl) return;
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
			if(prevProps.caption!=this.props.caption && this.props.caption){
				this.recalc();				
			}
			if(prevProps.caption && !this.props.caption)
				removeEventListener("resize",this.recalc)
			if(!prevProps.caption && this.props.caption)
				addEventListener("resize",this.recalc)
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
				padding:this.props.caption&&this.state.rotated?'0.5em 1em 1em 1.6em':'0.5em 0.5em 1em 0.5em',
				minHeight:this.state.rotated?this.state.containerMinHeight:"",
				...this.props.style
			};
			const captionStyle={
				color:"#727272",
				lineHeight:"1",
				marginLeft:this.state.rotated?"calc("+this.state.captionOffset+" - 1.7em)":"0em",
				position:this.state.rotated?"absolute":"static",
				transform:this.state.rotated?"rotate(-90deg)":"none",
				transformOrigin:"100% 0px",
				whiteSpace:"nowrap",
				marginTop:this.state.rotated?"1.5em":"0em",
				fontSize:"0.875em",
				display:"inline-block",
				...this.props.captionStyle
			};
			const emElStyle={
				position:"absolute",
				top:"0",
				zIndex:"-1",
				height:"1em"
			}
			const captionEl = this.props.caption? React.createElement("div",{ref:ref=>this.captionEl=ref,style:captionStyle,key:"caption"},this.props.caption): null;
			const emRefEl = React.createElement("div",{ref:ref=>this.emEl=ref,key:"emref",style:emElStyle});
			return React.createElement("div",{ref:ref=>this.groupEl=ref,style:style},[				
				captionEl,
				emRefEl,
				this.props.children
			])
		}	
	}); 
	FlexGroup.defaultProps = {
		bp:"15"
	};
	const ChipElement = ({value,style,onClick,children}) => React.createElement("div",{style:{
		fontSize:'1em',
		color:'white',
		textAlign:'center',
		borderRadius:'0.28em',
		//border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} transparent`,		
		backgroundColor:"#eee",
		cursor:'default',
		//width:'3.8em',
		display:'inline-block',				
		margin:'0 0.1em',
		verticalAlign:"top",
		paddingTop:"0.05em",
		paddingBottom:"0.2em",
		paddingLeft:"0.4em",
		paddingRight:children?"0em":"0.4em",		
		whiteSpace:"nowrap",
		alignSelf:"center",
		...style
	},onClick},[value,children])
	const ChipDeleteElement = ({style,onClick}) =>React.createElement(Interactive,{},(actions)=>{
			const fillColor = style&&style.color?style.color:"black";
			const svg = `
			<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 348.333 348.334" style="enable-background:new 0 0 348.333 348.334;" xml:space="preserve" fill="${fillColor}">
				<path d="M336.559,68.611L231.016,174.165l105.543,105.549c15.699,15.705,15.699,41.145,0,56.85 c-7.844,7.844-18.128,11.769-28.407,11.769c-10.296,0-20.581-3.919-28.419-11.769L174.167,231.003L68.609,336.563 c-7.843,7.844-18.128,11.769-28.416,11.769c-10.285,0-20.563-3.919-28.413-11.769c-15.699-15.698-15.699-41.139,0-56.85   l105.54-105.549L11.774,68.611c-15.699-15.699-15.699-41.145,0-56.844c15.696-15.687,41.127-15.687,56.829,0l105.563,105.554   L279.721,11.767c15.705-15.687,41.139-15.687,56.832,0C352.258,27.466,352.258,52.912,336.559,68.611z"/>
			</svg>`;
			const svgData = svgSrc(svg)
			const deleteEl = React.createElement("img",{src:svgData,style:{height:"0.6em",verticalAlign:"middle"}},null);
			return React.createElement("div",{style:{
				//"float":"right",
				//color:"#666",
				width:"0.8em",
				cursor:"pointer",
				//height:"100%",
				display:"inline-block",
				//borderRadius:"0 0.3em 0.3em 0",
				//backgroundColor:"transparent",			
				...style
			},onMouseOver:actions.onMouseOver,onMouseOut:actions.onMouseOut,onClick},React.createElement("span",{style:{
				fontSize:"0.7em",
				position:"relative",
				bottom:"calc(0.1em)"
			}},deleteEl))
		}
	)	
	
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
				fontSize:'1em',
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
				this.props.onChange({target:{headers:{"X-r-action":"change"},value:""}});
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
				fontSize:'1.85em',
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
				overflow:"hidden",
				fontSize:"0.7em"
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
						...[7,8,9].map(e=>React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:e,fkey:e.toString()},e.toString())),						  
						React.createElement(VKTd,{rowSpan:'2',onClickValue:this.props.onClickValue,style:{...specialTdAccentStyle,height:"2rem"},key:"4",fkey:"backspace"},backSpaceEl)
				   ]),					   
				   React.createElement("tr",{key:"4"},[
						...[4,5,6].map(e=>React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:e,fkey:e.toString()},e.toString()))						  				   
				   ]),
				   React.createElement("tr",{key:"5"},[
					   ...[1,2,3].map(e=>React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:e,fkey:e.toString()},e.toString())),
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
				!this.props.simple?React.createElement("table",{style:{...aTableStyle,fontSize:tableStyle.fontSize},key:"1"},
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
							React.createElement("td",{style:{...aKeyCellStyle,fontSize:"1em",backgroundColor:'transparent',border:'none'},colSpan:"9",key:"1"},[
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
	const THElement = React.createClass({
		getInitialState:function(){
			return {last:false,focused:false}
		},
		onFocus:function(){
			focusModule.switchTo(this)
			this.setState({focused:true})
		},
		onBlur:function(){
			this.setState({focused:false})
		},
		checkForSibling:function(){
			if(!this.el) return;
			if(!this.el.nextElementSibling) if(!this.state.last) this.setState({last:true})
			if(this.el.nextElementSibling) if(this.state.last) this.setState({last:false})	
		},
		componentDidMount:function(){
			this.checkForSibling()
			if(this.el && this.el.tagName=="TD") {
				this.el.addEventListener("focus",this.onFocus,true)
				this.el.addEventListener("blur",this.onBlur)	
				this.binding = focusModule.reg(this)
			}
		},
		componentDidUpdate:function(prevProps,_){
			this.checkForSibling()
			if(!this.props.droppable2) return;
			if(prevProps.mouseEnter!=this.props.mouseEnter){
				if(this.props.mouseEnter&&this.props.onDragDrop&&dragDropModule.onDrag())
					this.props.onDragDrop("dragOver","")
			}
		},
		componentWillUnmount:function(){
			if(this.dragBinding) this.dragBinding.release();
			if(this.el) this.el.removeEventListener("focus",this.onFocus)
			if(this.binding) this.binding.unreg()
		},
		signalDragEnd:function(outside){
			if(!this.props.draggable) return;
			if(!this.props.onDragDrop) return;			
			if(outside) this.props.onDragDrop("dragEndOutside","")
			else this.props.onDragDrop("dragEnd","")
		},
		onMouseDown:function(e){
			if(!this.props.draggable) return;
			if(!this.el) return;
			this.dragBinding = dragDropModule.dragStart(e.clientX,e.clientY,this.el,this.props.dragData,this.signalDragEnd);
			if(this.props.dragData && this.props.onDragDrop)
				this.props.onDragDrop("dragStart","")
			e.preventDefault();
		},
		onMouseUp:function(e){
			if(!this.props.droppable) return;
			const data = dragDropModule.onDrag()&&dragDropModule.getData()
			if(data && this.props.onDragDrop){
				dragDropModule.release()
				e.stopPropagation();
				this.props.onDragDrop("dragDrop",data)
			}
		},
		render:function(){
			const {style,colSpan,children} = this.props
			const nodeType = this.props.nodeType?this.props.nodeType:"th"
			//const hightlight = this.props.droppable&&this.props.mouseEnter&&dragDropModule.onDrag()
			const tabIndex = this.props.tabIndex?{tabIndex:this.props.tabIndex}:{}
			return React.createElement(nodeType,{style:{
				borderBottom:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #b6b6b6`,
				borderLeft:'none',
				borderRight:!this.state.last?`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #b6b6b6`:"none",
				borderTop:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #b6b6b6`,
				fontWeight:'bold',
				padding:'0.04em 0.08em 0.04em 0.08em',
				verticalAlign:'middle',
				overflow:"hidden",				
				textOverflow:"ellipsis",
				cursor:this.props.draggable?"move":"auto",
				backgroundColor:"transparent",
				outline:this.state.focused?"1px dotted black":"none",				
				...style
			},colSpan,
			ref:ref=>this.el=ref,
			onMouseDown:this.onMouseDown,
			onTouchStart:this.onMouseDown,			
			onMouseEnter:this.props.onMouseEnter,
			onMouseLeave:this.props.onMouseLeave,
			onMouseUp:this.onMouseUp,
			onTouchEnd:this.onMouseUp,
			...tabIndex
			},children)
		}
	})
	const TDElement = (props) =>{		
		if(props.droppable)
			return React.createElement(Interactive,{},actions=>React.createElement(THElement,{...props,style:{padding:'0.1em 0.2em',fontSize:'1em',fontWeight:'normal',borderBottom:'none',...props.style},nodeType:"td",...actions,tabIndex:"1"}))
		else	
			return React.createElement(THElement,{...props,style:{padding:'0.1em 0.2em',fontSize:'1em',fontWeight:'normal',borderBottom:'none',...props.style},nodeType:"td",tabIndex:"1"})
	}	
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
			return {mouseOver:false,mouseEnter:false};
		},
		onMouseOver:function(e){
			this.setState({mouseOver:true});
		},
		onMouseOut:function(e){
			this.setState({mouseOver:false});
		},
		onMouseEnter:function(e){
			this.setState({mouseEnter:true});
		},
		onMouseLeave:function(e){
			this.setState({mouseEnter:false});
		},
		render:function(){ 
			return this.props.children({
				onMouseOver:this.onMouseOver,
				onMouseOut:this.onMouseOut,
				onMouseEnter:this.onMouseEnter,
				onMouseLeave:this.onMouseLeave,
				mouseOver:this.state.mouseOver,
				mouseEnter:this.state.mouseEnter
			});
		}
	});
	const InputElementBase = React.createClass({			
		setFocus:function(focus){
			if(!focus) return
			this.getInput().focus()			
		},
		onKeyDown:function(e){
			if(!this.inp) return
			if(e.key == "Escape"){
				if(this.prevval != undefined) this.getInput().value = this.prevval
				this.prevval = undefined
				this.getInput().blur()
			}
			if(this.props.onKeyDown && !this.props.onKeyDown(e)) return			
			/*if(e.keyCode == 13) {
				if(this.inp2) this.inp2.blur()
				else this.inp.blur()
			}*/
		},
		doIfNotFocused:function(what){
			const inp = this.getInput()
			const aEl = documentManager.activeElement()
			log(inp)
			log(aEl)
			if(inp != aEl) {
				this.setFocus(true)
				what(inp)
				return true
			}
			return false
		},
		getInput:function(){ return this.inp||this.inp2},
		onEnter:function(event){
			//log(`Enter ;`)
			if(!this.doIfNotFocused((inp)=>{
				this.prevval = inp.value
				inp.selectionEnd = inp.value.length
				inp.selectionStart = inp.value.length
			}))	{
				if(!this.props.waitForServer){
					const cEvent = eventManager.create("cTab",{bubbles:true})
					this.cont.dispatchEvent(cEvent)
				}
			}
			event.stopPropagation()
		},
		onDelete:function(event){
			//log(`Delete`)
			this.doIfNotFocused((inp)=>{				
				this.prevval = inp.value
				inp.value = ""
			})					
			event.stopPropagation()
		},
		onPaste:function(event){
			//log(`Paste`)
			this.doIfNotFocused((inp)=>{				
				this.prevval = inp.value
				inp.value = event.detail
			})				
			event.stopPropagation()
		},
		onCopy:function(event){
			//log(`Copy`)
			this.doIfNotFocused((inp)=>{				
				this.prevval = inp.value
				inp.setSelectionRange(0,inp.value.length)
				documentManager.execCopy()
			})				
			event.stopPropagation()
		},
		componentDidMount:function(){
			//this.setFocus(this.props.focus)
			const inp = this.getInput()			
			inp.addEventListener('enter',this.onEnter)
			inp.addEventListener('delete',this.onDelete)
			inp.addEventListener('cpaste',this.onPaste)
			inp.addEventListener('ccopy',this.onCopy)
		},
		componentWillUnmount:function(){
			const inp = this.getInput()			
			inp.removeEventListener('enter',this.onEnter)
			inp.removeEventListener('delete',this.onDelete)
			inp.removeEventListener('cpaste',this.onPaste)
			inp.removeEventListener('ccopy',this.onCopy)
		},
		//componentDidUpdate:function(){this.setFocus(this.props.focus)},		
		onChange:function(e){
			if(this.inp&&getComputedStyle(this.inp).textTransform=="uppercase"){
				const newVal = e.target.value.toUpperCase();
				e.target.value = newVal;
			}
			if(this.props.onChange) this.props.onChange({target:{headers:{"X-r-action":"change"},value:e.target.value}})
		},
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
				display:"flex"
			};
			const inputStyle={
				textOverflow:"ellipsis",
				margin:"0rem",
				verticalAlign:"top",
				color:"rgb(33,33,33)",
				border:"none",
				height:this.props.div?"auto":"100%",
				padding:"0.2172em 0.3125em 0.2172em 0.3125em",
				width:"100%",
				zIndex:"0",
				boxSizing:"border-box",
				MozAppearence:"none",
				whiteSpace:this.props.div?"normal":"nowrap",
				overflow:"hidden",
				fontSize:"inherit",
				textTransform:"inherit",
				backgroundColor:"inherit",
				outline:"none",
				textAlign:"inherit",
				display:this.props.div?"inline-block":"",
				fontFamily:"inherit",
				...this.props.inputStyle				
			};		
			const placeholder = this.props.placeholder?this.props.placeholder:"";
			const inputType = this.props.inputType;//this.props.inputType?this.props.inputType:"input"
			const type = this.props.type?this.props.type:"text"
			const readOnly = (this.props.onChange||this.props.onBlur)?null:"true";
			const rows= this.props.rows?this.props.rows:"2";
			const content = this.props.content;
			const actions = {onMouseOver:this.props.onMouseOver,onMouseOut:this.props.onMouseOut};
			const overRideInputStyle = this.props.div?{display:"flex",flexWrap:"wrap",padding:"0.24em 0.1em",width:"auto"}:{}			
			return React.createElement("div",{style:inpContStyle,ref:(ref)=>this.cont=ref,...actions},[
					this.props.shadowElement?this.props.shadowElement():null,
					React.createElement("div",{key:"xx",style:inp2ContStyle},[
						React.createElement(inputType,{
							key:"1",
							ref:(ref)=>this.inp=ref,
							type,rows,readOnly,placeholder,
							content,							
							style:{...inputStyle,...overRideInputStyle},							
							onChange:this.onChange,onBlur:this.props.onBlur,onKeyDown:this.onKeyDown,value:!this.props.div?this.props.value:"",						
							},this.props.div?[this.props.inputChildren,
								React.createElement("input",{style:{...inputStyle,alignSelf:"flex-start",flex:"1 1 20%",padding:"0px"},ref:ref=>this.inp2=ref,key:"input",onChange:this.onChange,onBlur:this.props.onBlur,onKeyDown:this.onKeyDown,value:this.props.value})
							]:(content?content:null)),							
						this.props.popupElement?this.props.popupElement():null
					]),
					this.props.buttonElement?this.props.buttonElement():null
				]);					
		},
	});
	const InputElement = (props) => React.createElement(Interactive,{},(actions)=>React.createElement(InputElementBase,{...props,ref:props._ref,inputType:props.div?"div":"input",...actions}))	
	const TextAreaElement = (props) => React.createElement(Interactive,{},(actions)=>React.createElement(InputElementBase,{...props,onKeyDown:()=>false,ref:props._ref,inputType:"textarea",
		inputStyle:{
			whiteSpace:"pre-wrap",
			...props.inputStyle
		},
		...actions}))
	const LabeledTextElement = (props) => React.createElement(InputElementBase,{
		...props,
		onKeyDown:()=>false,
		inputType:"div",
		inputStyle:{
			...props.inputStyle,
			display:"inline-block"
		},
		style:{
			...props.style,
			backgroundColor:"transparent",
			borderColor:"transparent",
			lineHeight:"normal"
		},
		content:props.value
		})
	const DropDownElement = React.createClass({
		getInitialState:function(){
			return {popupMinWidth:0,left:null,top:null};
		},
		getPopupPos:function(){
			if(!this.inp||!this.inp.cont) return {};
			const rect = this.inp.cont.getBoundingClientRect()
			let res = {}
			if(this.pop){
				const popRect = this.pop.getBoundingClientRect()
				const windowRect = getWindowRect()								
				const rightEdge = rect.right + popRect.width
				const leftEdge = rect.left - popRect.width
				const bottomEdge = rect.bottom + popRect.height
				const topEdge = rect.top - popRect.height;
				if(bottomEdge<=windowRect.bottom){					//bottom
					const leftOffset = 0//rect.left - popRect.left
					const topOffset = rect.height
					//log("a")					
					if(this.state.top!=topOffset||this.state.left!=leftOffset)
						res = {...res,left:leftOffset,top:topOffset}
				}
				else if(topEdge>windowRect.top){	//top
					const topOffset = - popRect.height
					const leftOffset = 0//rect.left - popRect.left;
					//log("b")					
					if(this.state.top!=topOffset||this.state.left!=leftOffset)
						res = {...res,left:leftOffset,top:topOffset}				
				}
				else if(leftEdge>windowRect.left){	//left
					const leftOffset = - popRect.width;
					const topOffset = - popRect.height/2;
					//log("c")					
					if(this.state.left!=leftOffset||this.state.top!=topOffset)
						res = {...res,left:leftOffset,top:topOffset}
				}
				else if(rightEdge<=windowRect.right){
					const leftOffset = rect.width;
					const topOffset = - popRect.height/2;
					//log("d")					
					if(this.state.left!=leftOffset||this.state.top!=topOffset)
						res = {...res,left:leftOffset,top:topOffset}
				}
			}
			return res;
		},
		onChange:function(e){
			if(this.props.onChange)
				this.props.onChange({target:{headers:{"X-r-action":"change"},value:e.target.value}});
		},
		onClick:function(e){
			if(this.props.onClick)
				this.props.onClick(e);
		},
		onKeyDown:function(e){
			let call=""
			switch(e.key){
				case "Enter":					
				case "ArrowUp":										
				case "ArrowDown":
					call = e.key;
					e.preventDefault();
					break;
				case "Backspace":
					if(e.target.value.length == 0)
						call = e.key;					
					break;
			}
			if(call.length>0 && this.props.onClickValue)
				this.props.onClickValue("key",call);			
			return false;
		},
		getPopupWidth:function(){
			if(!this.inp||!this.inp.cont) return {};
			const minWidth = this.inp.cont.getBoundingClientRect().width;
			if(Math.round(this.state.popupMinWidth) != Math.round(minWidth)) return {popupMinWidth:minWidth};
			return {};
		},
		mixState:function(){
			const nW = this.getPopupWidth()
			const nR = this.getPopupPos()
			const state = {...nW,...nR}
			//log(state)
			if(Object.keys(state).length>0) this.setState(state)
		},
		componentDidMount:function(){
			this.mixState()
		},
		componentDidUpdate:function(){
			this.mixState()
		},			
		render:function(){
			//const topPosStyle = this.state.bottom?{top:'',marginTop:-this.state.bottom+"px"}:{top:this.state.top?this.state.top+getPageYOffset()+"px":''}
			const popupStyle={
				position:"absolute",
				border: `${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} black`,
				minWidth: this.state.popupMinWidth + "px",
				overflow: "auto",				
				maxHeight: "10em",				
				backgroundColor: "white",
				zIndex: "666",
				boxSizing:"border-box",
				overflowX:"hidden",
				//marginLeft:"",
				lineHeight:"normal",
				marginLeft:`calc(-${GlobalStyles.borderWidth} + ${this.state.left?this.state.left+"px":"0px"})`,
				marginTop:`calc(-${GlobalStyles.borderWidth} + ${this.state.top?this.state.top+"px":"0px"})`,
				//left:this.state.left?this.state.left+"px":"",
				//top:this.state.top?this.state.top + getPageYOffset() + "px":"",
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
			const value = this.props.value
			const inputChildren = this.props.div? this.props.children.slice(0,parseInt(this.props.div)): null
			const popupElement = () => [this.props.open?React.createElement("div",{key:"popup",style:popupStyle,ref:ref=>this.pop=ref},this.props.div?this.props.children.slice(parseInt(this.props.div)):this.props.children):null];
			
			return React.createElement(InputElement,{...this.props,inputChildren,value,_ref:(ref)=>this.inp=ref,buttonElement,popupElement,onChange:this.onChange,onBlur:this.props.onBlur,onKeyDown:this.onKeyDown});							
		}
	});	
	const ButtonInputElement = (props) => React.createElement(Interactive,{},(actions)=>{
		const openButtonWrapperStyle= {				
			flex:"1 1 0%",
			height:"auto",
			minHeight:"100%",
			overflow:"hidden",
			backgroundColor:"transparent",
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
		
		return React.createElement("div",{key:"inputButton",style:openButtonWrapperStyle},
			React.createElement(ButtonElement,{...props,...actions,style:openButtonStyle})
		);
	})
	const ControlWrapperElement = React.createClass({
		getInitialState:function(){
			return {focused:false}
		},
		onFocus:function(){
			focusModule.switchTo(this)
			this.setState({focused:true})
		},
		onBlur:function(){
			this.setState({focused:false})
		},
		componentDidMount:function(){
			if(this.el) {
				this.el.addEventListener("focus",this.onFocus,true)
				this.el.addEventListener("blur",this.onBlur,false)
			}
			this.binding = focusModule.reg(this)
		},
		componentWillUnmount:function(){
			if(this.el) {
				this.el.removeEventListener("focus",this.onFocus)
				this.el.removeEventListener("blur",this.onBlur)
			}
			this.binding.unreg()
		},
		render:function(){
			const className = this.props.focusMarker?`marker-${this.props.focusMarker}`:""
			const {style,children} = this.props
			return React.createElement("div",{style:{
				width:"100%",				
				padding:"0.4em 0.3125em",
				boxSizing:"border-box",
				outline:this.state.focused?"1px dotted black":"none",
				className,
				...style
			},tabIndex:"1",ref:ref=>this.el=ref},children);
		}
	})	
	
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
				this.props.onChange({target:{headers:{"X-r-action":"change"},value:state}});				
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
	const Checkbox = (props) => React.createElement(Interactive,{},(actions)=>React.createElement(CheckboxBase,{...props,...actions}))
	const CheckboxBase = React.createClass({
		getInitialState:function(){
			return {focused:false}
		},
		onFocus:function(){
			focusModule.switchTo(this)
			this.setState({focused:true})
		},
		onBlur:function(){
			this.setState({focused:false})
		},
		onSpace:function(event){
			this.onClick()
			event.stopPropagation()
		},
		componentDidMount:function(){
			if(this.el) {
				this.el.addEventListener("focus",this.onFocus,true)
				this.el.addEventListener("blur",this.onBlur)
				this.el.addEventListener("cspace",this.onSpace)
			}
			this.binding = focusModule.reg(this)
		},
		componentWillUnmount:function(){
			if(this.el) {
				this.el.removeEventListener("focus",this.onFocus)
				this.el.removeEventListener("blur",this.onBlur)
				this.el.removeEventListener("cspace",this.onSpace)
			}
			if(this.binding) this.binding.unreg()
		},
		onClick:function(){
			if(this.props.onChange) 
				this.props.onChange({target:{headers:{"X-r-action":"change"},value:(this.props.value?"":"checked")}})					
		},
		render:function(){
			const props = this.props			
			const style={
				flexGrow:"0",				
				position:"relative",
				maxWidth:"100%",
				padding:"0.4em 0.3125em",				
				flexShrink:"1",
				boxSizing:"border-box",
				lineHeight:"1",
				outline:this.state.focused?"1px dotted black":"none",
				...props.altLabel?{margin:"0.124em 0em",padding:"0em"}:null,
				...props.style
			};
			const innerStyle={
				border:"none",
				display:"inline-block",
				lineHeight:"100%",
				margin:"0rem",				
				outline:"none",				
				whiteSpace:"nowrap",
				width:props.label?"calc(100% - 1em)":"auto",
				cursor:"pointer",
				bottom:"0rem",
				...props.innerStyle
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
				borderColor:props.mouseOver?"black":"rgb(182, 182, 182)",
				backgroundColor:props.onChange?"white":"#eeeeee",
				...props.altLabel?{height:"1.655em",width:"1.655em"}:null,
				...props.checkBoxStyle
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
				...props.labelStyle
			};
			const imageStyle = {				
				bottom:"0rem",
				height:"90%",
				width:"100%",
			};	
			
			const svg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="16px" viewBox="0 0 128.411 128.411"><polygon points="127.526,15.294 45.665,78.216 0.863,42.861 0,59.255 44.479,113.117 128.411,31.666"/></svg>';
			const svgData=svgSrc(svg);
			const defaultCheckImage = props.value&&props.value.length>0?React.createElement("img",{style:imageStyle,src:svgData,key:"checkImage"},null):null
			const labelEl = props.label?React.createElement("label",{style:labelStyle,key:"2"},props.label):null;
			const checkImage = props.checkImage?props.checkImage:defaultCheckImage;
			const {onMouseOver,onMouseOut} = props		
			return React.createElement("div",{style,tabIndex:"1",ref:ref=>this.el=ref},
				React.createElement("span",{onMouseOver,onMouseOut,style:innerStyle,key:"1",onClick:this.onClick},[
					React.createElement("span",{style:checkBoxStyle,key:"1"},checkImage),
					labelEl
				])
			);
		}
	})
	
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
	const ConnectionState =(props)=>{
		const {style,iconStyle,on} = props;
		const fillColor = style.color?style.color:"black";
		const contStyle={
			borderRadius:"1em",
			border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} ${fillColor}`,
			backgroundColor:on?"green":"red",		
			display:'inline-block',
			width:"1em",
			height:"1em",
			padding:"0.2em",
			boxSizing:"border-box",
			verticalAlign:"top",
			marginLeft:"0.2em",
			marginRight:"0.2em",
			alignSelf:"center",
			...style
		};
		const newIconStyle={
			//position:'relative',
			//top:'-0.05em',
			//left:'-0.025em',
			verticalAlign:"top",
			width:"0.5em",
			//lineHeight:"1",			
			...iconStyle
		};			
			
		const imageSvg='<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 285.269 285.269" style="enable-background:new 0 0 285.269 285.269;" xml:space="preserve"> <path style="fill:'+fillColor+';" d="M272.867,198.634h-38.246c-0.333,0-0.659,0.083-0.986,0.108c-1.298-5.808-6.486-10.108-12.679-10.108 h-68.369c-7.168,0-13.318,5.589-13.318,12.757v19.243H61.553C44.154,220.634,30,206.66,30,189.262 c0-17.398,14.154-31.464,31.545-31.464l130.218,0.112c33.941,0,61.554-27.697,61.554-61.637s-27.613-61.638-61.554-61.638h-44.494 V14.67c0-7.168-5.483-13.035-12.651-13.035h-68.37c-6.193,0-11.381,4.3-12.679,10.108c-0.326-0.025-0.653-0.108-0.985-0.108H14.336 c-7.168,0-13.067,5.982-13.067,13.15v48.978c0,7.168,5.899,12.872,13.067,12.872h38.247c0.333,0,0.659-0.083,0.985-0.107 c1.298,5.808,6.486,10.107,12.679,10.107h68.37c7.168,0,12.651-5.589,12.651-12.757V64.634h44.494 c17.398,0,31.554,14.262,31.554,31.661c0,17.398-14.155,31.606-31.546,31.606l-130.218-0.04C27.612,127.862,0,155.308,0,189.248 s27.612,61.386,61.553,61.386h77.716v19.965c0,7.168,6.15,13.035,13.318,13.035h68.369c6.193,0,11.381-4.3,12.679-10.108 c0.327,0.025,0.653,0.108,0.986,0.108h38.246c7.168,0,12.401-5.982,12.401-13.15v-48.977 C285.269,204.338,280.035,198.634,272.867,198.634z M43.269,71.634h-24v-15h24V71.634z M43.269,41.634h-24v-15h24V41.634z M267.269,258.634h-24v-15h24V258.634z M267.269,228.634h-24v-15h24V228.634z"/></svg>';
		const imageSvgData = svgSrc(imageSvg);		
		const src = props.imageSvgData || imageSvgData
		return React.createElement("div",{style:contStyle},
				React.createElement("img",{key:"1",style:newIconStyle,src},null)				
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
			const shadowElement = () => [React.createElement("input",{key:"0",ref:(ref)=>this.fInp=ref,onChange:this.onChange,type:"file",style:{visibility:"hidden",position:"absolute",height:"1px",width:"1px"}},null)];
			const buttonElement = () => [React.createElement(ButtonInputElement,{key:"2",onClick:this.onClick},buttonImage)];
			
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
				React.createElement(ControlWrapperElement,{key:"1",style:{flex:"1 1 0%"}},
					React.createElement(LabelElement,{label:passwordCaption},null),
					React.createElement(InputElement,{...attributesA,focus:prop.focus,type:"password"},null)			
				),
				React.createElement(ControlWrapperElement,{key:"2",style:{flex:"1 1 0%"}},
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
		const styleA = {
			...attributesA.style,
			//textTransform:"none"
		}
		const styleB = {
			...attributesB.style,
			textTransform:"none"
		}
        return React.createElement("div",{style:{margin:"1em 0em",...prop.style}},
			React.createElement("form",{key:"form",onSubmit:e=>e.preventDefault()},[
				React.createElement(ControlWrapperElement,{key:"1"},
					React.createElement(LabelElement,{label:usernameCaption},null),
					React.createElement(InputElement,{...attributesA,style:styleA,focus:prop.focus},null)			
				),
				React.createElement(ControlWrapperElement,{key:"2"},
					React.createElement(LabelElement,{label:passwordCaption},null),
					React.createElement(InputElement,{...attributesB,style:styleB,onKeyDown:()=>false,focus:false,type:"password"},null)			
				),
				React.createElement("div",{key:"3",style:{textAlign:"right",paddingRight:"0.3125em"}},
					React.createElement(ButtonElement,{onClick:prop.onBlur,style:buttonStyle,overStyle:buttonOverStyle},buttonCaption)
				)
			])
		)
	}	
	
	const CalenderCell = (props) => React.createElement(Interactive,{},(actions)=>{		
		const onClick = () =>{
			const monthAdj = props.m=="p"?"-1":props.m=="n"?"1":"0"
			const value = "month:"+monthAdj+";day:"+props.curday.toString();
			if(props.onClickValue) props.onClickValue("change",value);
		}
		const isSel = props.curday == props.curSel;
		const style ={
			width:"12.46201429%",
			margin:"0 0 0 2.12765%",											
			textAlign:"center",
			border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle}`,
			borderColor:!props.m?"#d4e2ec":"transparent",
			backgroundColor:isSel?"transparent":(actions.mouseOver?"#c0ced8":"transparent")				
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
		const {onMouseOver,onMouseOut} = actions
		return React.createElement("div",{onClick,style,onMouseOver,onMouseOut},
			React.createElement("div",{style:cellStyle},
				React.createElement("a",{style:aCellStyle},props.curday)
			)
		);
	})
	const CalendarYM = (props) => React.createElement(Interactive,{},(actions) => {
		const style={
			width:"12.46201429%",
			margin:"0 0 0 2.12765%",						
			textAlign:"center",
			backgroundColor:actions.mouseOver?"#c0ced8":"transparent",
			color:actions.mouseOver?"#212112":"white",
			...props.style
		};
		const {onMouseOver,onMouseOut} = actions
		return React.createElement("div",{onClick:props.onClick,style,onMouseOver,onMouseOut},props.children);		
	})
	const CalenderSetNow = (props) => React.createElement(Interactive,{},(actions)=>{
		const style={
			border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #d4e2ec`,
			color:"#212121",
			cursor:"pointer",
			display:"inline-block",
			padding:".3125em 2.3125em",
			backgroundColor:actions.mouseOver?"#c0ced8":"transparent",
			...props.style
		};
		const {onMouseOver,onMouseOut} = actions
		return React.createElement("div",{style,onClick:props.onClick,onMouseOver,onMouseOut},props.children);
	})
	const CalenderTimeButton = (props) => React.createElement(Interactive,{},(actions)=>{
		const style = {
			backgroundColor:actions.mouseOver?"#c0ced8":"transparent",
			border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #d4e2ec`,
			cursor:"pointer",
			padding:"0.25em 0",
			width:"2em",
			fontSize:"1em",
			outline:"none",
			...props.style
		};
		const {onMouseOver,onMouseOut} = actions
		return React.createElement("button",{style,onClick:props.onClick,onMouseOver,onMouseOut},props.children);
	})
	const DateTimePickerYMSel = ({month,year,onClickValue,monthNames}) => {
		const defaultMonthNames=["January","February","March","April","May","June","July","August","September","October","November","December"];
		const aMonthNames = monthNames&&monthNames.length==12?monthNames:defaultMonthNames;
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
			React.createElement(CalendarYM,{key:"3",style:ymStyle},aItem(aMonthNames[selMonth]+" "+year,{padding:"0.325em 0 0.325em 0",cursor:"default"})),
			React.createElement(CalendarYM,{onClick:changeMonth(1),key:"4"},aItem("〉")),
			React.createElement(CalendarYM,{onClick:changeYear(1),key:"5"},aItem("+"))					
		]);
		
	};
	const DateTimePickerDaySel = ({month,year,curSel,onClickValue,dayNames}) => {
		const defaultDayNames  = ["Mon","Tue","Wed","Thu","Fri","Sat","Sun"];
		const aDayNames = dayNames&&dayNames.length>0?dayNames:defaultDayNames;
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
		const rowsOfDays = (dayArray,cDay) => {
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
				aDayNames.map((day,i)=>
					React.createElement("div",{key:"d"+i,style:dayOfWeekStyle},
						React.createElement("div",{style:dayStyle},day)
					)
				)
			]),
			rowsOfDays(cal_makeDaysArr(month,year),{month,year})
		]);
	}
	const DateTimePickerTSelWrapper = ({children}) => {
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
	const DateTimePickerTimeSel = ({hours,mins,onClickValue}) => {		
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
	const DateTimePickerNowSel = ({onClick,value}) => React.createElement(CalenderSetNow,{key:"setNow",onClick:onClick},value);
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
		const getParts = (value,selectionStart,selectionEnd) => {
			const arr = value.split(/[-\s]/)			
			const dat = [];
			arr.forEach(v=>{				
				const start = value.indexOf(v,dat[dat.length-1]?dat[dat.length-1].end:0)
				const end = start + v.length
				const selected = selectionStart>=start&&selectionEnd<=end?true:false
				dat.push({start,end,selected})
			})
			return dat;
		}
		const setSelection = (obj, stpos, endpos) => {
			if (obj.createTextRange) { // IE
				const rng = obj.createTextRange();
				rng.moveStart('character', stpos);
				rng.moveEnd('character', endpos - obj.value.length);				
				rng.select();
			}
			else if (obj.setSelectionRange) { // FF
				obj.setSelectionRange(stpos, endpos);
			}
		}
		const funcMap = ["day","month","year","hour","min"];
		const onClickValue = (func,adj) =>{
			if(props.onClickValue) props.onClickValue("change",func+":"+adj.toString());
		}
		const onKeyDown = e =>{			
			const inp = e.target
			const val = inp.value
			const dat = getParts(val,inp.selectionStart,inp.selectionEnd)
			const selD = dat.find(d=>d.selected==true)
			let func = ""
			//s
			switch(e.keyCode){
				case 38:	//arrow up					
					setSelection(inp,selD.start,selD.end)
					e.preventDefault()
					func = funcMap[dat.indexOf(selD)]
					onClickValue(func,1)
					log(`send: ${func}:1`)					
					return					
				case 40:	//arrow down
					log("send dec")
					setSelection(inp,selD.start,selD.end)
					e.preventDefault()
					func = funcMap[dat.indexOf(selD)]
					onClickValue(func,-1)
					log(`send: ${func}:-1`)
					return true
				case 27:	//esc
					log("esc")
					setSelection(inp,selD.end,selD.end)
					return true				
			}
			return true;
		}
		const inputStyle = {textAlign:"right"}
		
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
		return React.createElement(DropDownElement,{...props,inputStyle,popupStyle,onKeyDown,buttonImageStyle,url:urlData,children:calWrapper(props.children)});			
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
	const InternalClock = (()=>{
		let timeString = ""
		const callbacks = [];
		let timeout = null
		let time = 0;
		let bgTicks = 5;
		const prefix = (num) =>{if(num.length<2) return `0${num}`; else return `${num}`}
		const formatTime = () => {						
			const date = new Date(time)			
			return `${prefix(date.getUTCDate().toString())}-${prefix((date.getUTCMonth()+1).toString())}-${date.getUTCFullYear().toString()} ${prefix(date.getUTCHours().toString())}:${prefix(date.getUTCMinutes().toString())}:${prefix(date.getUTCSeconds().toString())}`;
		}
		const tick = () => {
			if(time){
				timeString = formatTime();
				time += 1000;
			}
			callbacks.forEach(o=>{
				if(o.updateInterval>=5*60){o.updateServer();o.updateInterval=0}
				o.updateInterval += 1
				o.clockTicks(timeString)
			})			
			timeout = setTimeout(tick,1000)			
			if(callbacks.length == 0 && bgTicks<=0) stop();
			else if(callbacks.length == 0 && bgTicks>0) bgTicks -=1;
		}	
		const get = () => timeString
		const start = ()=>{bgTicks = 5;tick();}
		const update = (updateTime) => {if(updateTime) time = updateTime}
		const stop = ()=>{clearTimeout(timeout); timeout==null}
		const reg = (obj) => {callbacks.push(obj); if(callbacks.length==1 && timeout==null) start(); return ()=>{const index = callbacks.indexOf(obj); if(index>=0) delete callbacks[index];};}
		return {reg,update,get}
	})()
	let dateElementPrevCutBy = 0;
	const OneSvg = React.createClass({
		render:function(){
			return React.createElement("svg",{},React.createElement("text",{style:{dominantBaseline:"hanging"}},this.props.children));
		}
	})
	
	const DateTimeClockElement = React.createClass({
		getInitialState:function(){
			return {cutBy:0,timeString:""}
		},
		clockTicks:function(timeString){
			this.setState({timeString})
		},
		isOverflow:function(){
			const childRect = this.shadowEl.getBoundingClientRect();			
			const parentRect = this.contEl.getBoundingClientRect();
			return childRect.width > parentRect.width
		},
		setCutBy:function(){
			const cutBy = !this.state.cutBy?1:0;
			this.setState({cutBy})
			dateElementPrevCutBy = cutBy
		},
		recalc:function(){			
			if(!this.contEl || !this.shadowEl) return;
			const isOverflow = this.isOverflow()
			if( isOverflow && this.state.cutBy == 0) this.setCutBy()
			else if(!isOverflow && this.state.cutBy == 1) this.setCutBy()
		},
		updateServer:function(){
			this.props.onClick()
			log("call update")
		},
		componentDidMount:function(){
			addEventListener("resize",this.recalc)			
			this.recalc()
			const clockTicks = this.clockTicks;
			const updateInterval = 5*60
			const updateServer = this.updateServer
			this.unreg = InternalClock.reg({clockTicks,updateInterval,updateServer})			
		},
		componentWillReceiveProps:function(nextProps){
			log("came update")
			InternalClock.update(parseInt(nextProps.time)*1000)
		},
		componentDidUpdate:function(_, prevState){
			if(prevState.timeString.length == 0 && this.state.timeString.length>0) this.recalc();
		},
		componentWillUnmount:function(){
			removeEventListener("resize",this.recalc)
			this.unreg()
		},
		render:function(){
			const fullTime = InternalClock.get()
			let partialTime ="";
			const cutBy  = !this.state.cutBy&&dateElementPrevCutBy!=this.state.cutBy?dateElementPrevCutBy:this.state.cutBy;
			dateElementPrevCutBy = cutBy;
			switch(cutBy){				
				case 1: const dateArr = fullTime.split(' ');
						partialTime = dateArr[1];
						break;
				default: partialTime = fullTime;break;
			}
			
			const style={				
				display:"inline-block",
				verticalAlign:"middle",
				alignSelf:"center",
				margin:"0 0.2em",
				minWidth:"0",
				whiteSpace:"nowrap",
				position:"relative",
				flex:"1 1 0%",				
				...this.props.style					
			}
			const shadowStyle = {				
				visibility:"hidden"
			}
			const textStyle = {
				position:"absolute",
				right:"0em"
			}
			//const localDate = new Date(serverTime)
			//const lastChar = partialTime[partialTime.length-1]
			return React.createElement("div",{style,ref:ref=>this.contEl=ref},[			
				React.createElement("span",{style:shadowStyle,ref:ref=>this.shadowEl=ref,key:"shadow"},fullTime),
				React.createElement("span",{style:textStyle,key:"date"},partialTime)
			]);
		}
	})
	const AnchorElement = ({style,href}) =>React.createElement("a",{style,href},"get")
	
	const HeightLimitElement = React.createClass({
		getInitialState:function(){
			return {max:false}
		},
		findUnder:function(el,rect){			
			const sibling = el.nextSibling
			if(!sibling) {
				const parentEl = el.parentNode
				if(isReactRoot(parentEl)) return false				
				return this.findUnder(parentEl,rect)
			}
			const sRect = sibling.getBoundingClientRect()			
			if(((sRect.left>=rect.left && sRect.left<=rect.right)||(sRect.right<=rect.right&&sRect.right>=rect.left))&&sRect.bottom>rect.bottom)
				return true
			else
				return this.findUnder(sibling,rect)
		},
		recalc:function(){
			if(!this.el) return;
			const rect = this.el.getBoundingClientRect();			
			const found = this.findUnder(this.el,rect)
			//log("return :"+found)
			if(this.state.max != !found)
				this.setState({max:!found})				
			
		},
		componentDidMount:function(){			
			checkActivateCalls.add(this.recalc)			
		},
		componentWillUnmount:function(){			
			checkActivateCalls.remove(this.recalc)			
		},
		render:function(){			
			const style = {
				maxHeight:this.state.max?"none":this.props.limit,
				...this.props.style
			}
			//log("state"+this.state.max)
			//log(style)
			return React.createElement("div",{style, ref:ref=>this.el=ref},this.props.children)
		}
	})
	
	
	const download = (data) =>{
		const anchor = documentManager.createElement("a")
		anchor.href = data
		anchor.download = data.split('/').reverse()[0]
		anchor.click()
	}
	

	const sendVal = ctx =>(action,value) =>{
		const act = action.length>0?action:"change"
		sender.send(ctx,({headers:{"X-r-action":act},value}));
	}
	const sendBlob = ctx => (name,value) => {sender.send(ctx,({headers:{"X-r-action":name},value}));}	
	const onClickValue = ({sendVal});
	const onDragDrop = ({sendVal});
	const onReadySendBlob = ({sendBlob});
	const transforms= {
		tp:{
            DocElement,FlexContainer,FlexElement,ButtonElement, TabSet, GrContainer, FlexGroup, VirtualKeyboard,
            InputElement,AnchorElement,HeightLimitElement,
			DropDownElement,ControlWrapperElement,LabeledTextElement,
			LabelElement,ChipElement,ChipDeleteElement,FocusableElement,PopupElement,Checkbox,
            RadioButtonElement,FileUploadElement,TextAreaElement,
			DateTimePicker,DateTimePickerYMSel,DateTimePickerDaySel,DateTimePickerTSelWrapper,DateTimePickerTimeSel,DateTimePickerNowSel,
			DateTimeClockElement,
            MenuBarElement,MenuDropdownElement,FolderMenuElement,ExecutableMenuElement,
            TableElement,THeadElement,TBodyElement,THElement,TRElement,TDElement,
            ConnectionState,
			SignIn,ChangePassword			
		},
		onClickValue,		
		onReadySendBlob,
		onDragDrop
	};
	const receivers = {
		download,
		...errors.receivers
	}	
	const checkActivate = checkActivateCalls.check	
	return ({transforms,receivers,checkActivate});
}
