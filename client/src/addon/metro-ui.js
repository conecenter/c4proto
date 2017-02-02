"use strict";
import React from 'react'

function MetroUi(sender){
	const FlexContainer = React.createClass({
		getInitialState:function(){
			return {};
		},
		render:function(){
			var style={
				display:'flex',
				flexWrap:this.props.flexWrap?this.props.flexWrap:'nowrap',
			};			
			if(this.props.style) Object.assign(style,this.props.style);
			return React.createElement("div",{style:style},this.props.children)
		}
	});
	const FlexElement = React.createClass({
		render:function(){
			var style={
				flexGrow:this.props.expand?'1':'0',
				flexShrink:'1',
				minWidth:'0px',
				flexBasis:this.props.minWidth?this.props.minWidth:'auto',
				maxWidth:this.props.maxWidth?this.props.maxWidth:'auto',
			};
			if(this.props.style) Object.assign(style,this.props.style);
			return React.createElement("div",{style:style},this.props.children)
		}
	});
	const button = React.createClass({    
		onClick:function(e){
			if(this.props.onClick){            
				this.props.onClick(e);
			}
		},
		onTouchStart:function(e){
			if(this.props.onTouchStart)
				this.props.onTouchStart(e);
		},
		onTouchEnd:function(e){
			if(this.props.onTouchEnd)
				this.props.onTouchEnd(e);
		},
		mouseOut:function(){
			if(this.props.onMouseOut)
				this.props.onMouseOut();
		},
		mouseOver:function(){
			if(this.props.onMouseOver)
				this.props.onMouseOver();
		},
		
		render:function(){
			var style={            
				border:'none',
				cursor:'pointer',
				paddingInlineStart:'6px',
				paddingInlineEnd:'6px',
				padding:'0 1rem',
				minHeight:'2rem',
				minWidth:'1rem',
				fontSize:'1rem',
			};
			if(this.props.style) Object.assign(style,this.props.style);
			return React.createElement('button',{style:style,onClick:this.onClick,onMouseOut:this.mouseOut,onMouseOver:this.mouseOver,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children);
		}
	});
	const CommonButton=React.createClass({
		getInitialState:function(){
			return {mouseOver:false};
		},
		mouseOver:function(){
			this.setState({mouseOver:true});
		},
		mouseOut:function(){
			this.setState({mouseOver:false});
		},
		onClick:function(e){
			if(this.props.onClick)
				this.props.onClick(e);
		},
		render:function(){
			var newStyle={
				backgroundColor:this.state.mouseOver?'#ffffff':'#eeeeee',
			};
			return React.createElement(button,{style:newStyle,onMouseOver:this.mouseOver,onMouseOut:this.mouseOut,onClick:this.onClick},this.props.children);
		}
	});
	const GotoButton=React.createClass({
		getInitialState:function(){
			return {mouseOver:false,touch:false};
		},
		mouseOver:function(){
			this.setState({mouseOver:true});
		},
		mouseOut:function(){
			this.setState({mouseOver:false});
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
			var selStyle={
				outline:this.state.touch?'0.1rem solid blue':'none',
				outlineOffset:'-0.1rem',
				backgroundColor:this.state.mouseOver?"#ffffff":"#eeeeee",
			}        
			
			if(this.props.style)
				Object.assign(selStyle,this.props.style);
			if(this.state.mouseOver)
				Object.assign(selStyle,this.props.overStyle);		
			return React.createElement(button,{style:selStyle,onMouseOver:this.mouseOver,onMouseOut:this.mouseOut,onClick:this.onClick,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children);
		}
	});
	const MenuBarElement=React.createClass({
		getInitialState:function(){
			return {};
		},
		render:function(){
			var style={
				display:'flex',
				flexWrap:'nowrap',
				justifyContent:'flex-start',
				//backgroundColor:'#c0ced8',
				verticalAlign:'middle',
			};
			if(this.props.style) Object.assign(style,this.props.style);
			return React.createElement("div",{style:style},this.props.children)
		}
	});
	const MenuDropdownElement=React.createClass({
		getInitialState:function(){
			return {};
		},
		componentDidUpdate(prevProps,prevState){
		    console.log(prevProps,this.props)
		},
		render:function(){
			var style={
				position:'absolute',
				borderRadius:'5%',
				minWidth:'7em',
				boxShadow:'0 0 1.25rem 0 rgba(0, 0, 0, 0.2)',
				zIndex:'10',
				transitionProperty:'all',
				transitionDuration:'0.15s',
				transformOrigin:'50% 0%',
			};
			if(this.props.style) Object.assign(style,this.props.style);
			return React.createElement("div",{style:style},this.props.children)
		}
	});
	const FolderMenuElement=React.createClass({
		getInitialState:function(){
			return {mouseEnter:false,touch:false};
		},
//        mouseOver:function(){
//            this.setState({mouseOver:true});
//        },
//        mouseOut:function(){
//            this.setState({mouseOver:false});
//        },
		mouseEnter:function(e){
			this.setState({mouseEnter:true});
//            if(this.props.onChange)
//                this.props.onChange({target:{value:"mouseEnter"}});

		},
		mouseLeave:function(e){
			this.setState({mouseEnter:false});
//            if(this.props.onChange)
//                this.props.onChange({target:{value:"mouseLeave"}});
		},
		onClick:function(e){
		    if(this.props.onClick)
		        this.props.onClick(e);
		},
		render:function(){		
			var selStyle={
				position:'relative',
                backgroundColor:'#c0ced8',
                whiteSpace:'nowrap',
                paddingRight:'0.8em',
			};        
			
			if(this.props.style)
				Object.assign(selStyle,this.props.style);
			//console.log(this.state);
			if(this.state.mouseEnter)
				Object.assign(selStyle,this.props.overStyle);		
			return React.createElement("div",{
			    style:selStyle,
			    onMouseEnter:this.mouseEnter,
			    onMouseLeave:this.mouseLeave,
			    onClick:this.onClick,
			    //onChange:this.props.onChange,
			    //onTouchStart:this.onTouchStart,
			    //onTouchEnd:this.onTouchEnd
			},this.props.children);
		}
	});
	const ExecutableMenuElement=React.createClass({
		getInitialState:function(){
			return {mouseOver:false};
		},
//		mouseOver:function(){
//			this.setState({mouseOver:true});
//		},
//		mouseOut:function(){
//			this.setState({mouseOver:false});
//		},
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
			var newStyle={
                minWidth:'7em',
                height:'2.5em',
                backgroundColor:'#c0ced8',
                cursor:'pointer',
			};

        if(this.props.style)
            Object.assign(newStyle,this.props.style);
        if(this.state.mouseEnter)
            Object.assign(newStyle,this.props.overStyle);
		return React.createElement("div",{
            style:newStyle,
    //		onMouseOver:this.mouseOver,
    //		onMouseOut:this.mouseOut,
            onMouseEnter:this.mouseEnter,
            onMouseLeave:this.mouseLeave,
            onClick:this.onClick
		},this.props.children);
		}
	});
	const TabSet=React.createClass({
		render:function(){
			var style={
				borderBottom:'0.05rem solid',         
				overflow:'hidden',
				display:'flex',
				marginTop:'0rem',
			};
			Object.assign(style,this.props.style);
			return React.createElement("div",{style:style},this.props.children);
		}
	});
	const DocElement=React.createClass({
		componentDidMount:function(){
			const node=document.querySelector("#content");	
			if(node)	
			while (node.hasChildNodes()) 
				node.removeChild(node.lastChild);
			document.body.style.margin="0rem";
			if(this.props.style)
				Object.assign(document.documentElement.style,this.props.style);
			
		},
		render:function(){		
			return React.createElement("div");
		}	
	});
	const GrContainer= React.createClass({
		render:function(){
			var style={
				boxSizing:'border-box',           
				fontSize:'0.875rem',
				lineHeight:'1.1rem',
				margin:'0px auto',
				paddingTop:'0.3125rem',
			};
			return React.createElement("div",{style:style},this.props.children);
		}
	});
	const FlexGroup=React.createClass({
		render:function(){
			var style={
				backgroundColor:'white',
				border:'0.02rem #b6b6b6 dashed',
				margin:'0.4rem',
				padding:'0.5rem 0.5rem 1.25rem 0.5rem',
			};
			if(this.props.style)
				Object.assign(style,this.props.style);
			return React.createElement("div",{style:style},this.props.children);
		}
	});
	const Chip = React.createClass({
		render:function(){
			var newStyle={
				fontWeight:'bold',
				fontSize:'1.4rem',
				color:'white',
				textAlign:'center',
				borderRadius:'0.58rem',
				border:'0.02rem solid '+(this.props.on?'#ffa500':'#eeeeee'),
				backgroundColor:(this.props.on?'#ffa500':'#eeeeee'),
				cursor:'default',
				width:'3.8rem',
				display:'block',
				marginBottom:'0.1rem',
			};
			if(this.props.style)
				Object.assign(newStyle,this.props.style);
			const value = this.props.children|| this.props.value;
			return React.createElement('input',{style:newStyle,readOnly:'readonly',value:value},null);
		}
	});
	
	const VKTd = React.createClass({
		getInitialState:function(){
			return {touch:false};
		},
		onClick:function(ev){
			if(this.props.onClick){
				this.props.onClick(ev);
				return;
			}
			if(this.props.fkey){
				var event=new KeyboardEvent("keydown",{key:this.props.fkey})
				window.dispatchEvent(event);
			}
			if(this.props.onClickValue)
				this.props.onClickValue(ev,this.props.fkey);
		},
		onTouchStart:function(e){
			this.setState({touch:true});
		},
		onTouchEnd:function(e){
			this.setState({touch:false});
		},
		render:function(){
			var bStyle={
				height:'100%',
				width:'100%',
				border:'none',
				fontStyle:'inherit',
				fontSize:'0.7em',
				backgroundColor:'inherit',
				verticalAlign:'top',
				outline:this.state.touch?'0.1rem solid blue':'none',
				color:'inherit',
			};
			if(this.props.bStyle)
				Object.assign(bStyle,this.props.bStyle);
			return React.createElement("td",{style:this.props.style,
								colSpan:this.props.colSpan,rowSpan:this.props.rowSpan,onClick:this.onClick},
								React.createElement("button",{style:bStyle,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children));
			},
	});
	const VirtualKeyboard = React.createClass({
	   /* getInitialState:function(){
			return {numeric:true};
		},*/
		switchMode:function(e){
			//if(this.state.numeric) this.setState({numeric:false});
			//else this.setState({numeric:true});
			if(this.props.onChange)
				this.props.onChange({target:{value:""}});
		},
		render:function(){
			var tableStyle={
				fontSize:'2rem',
				borderSpacing:'0.2rem',
				marginTop:'-0.2rem',
				marginLeft:'auto',
				marginRight:'auto',
			};
			var tdStyle={
				//padding:'0 .3125rem',
				textAlign:'center',
				verticalAlign:'middle',
				border:'0.01rem solid',
				backgroundColor:'#eeeeee',
				height:'2.2rem',
				width:'2rem',
				overflow:"hidden",
			};
			var aTableStyle={
				fontSize:'1.55rem',
				borderSpacing:'0.3rem',
				marginTop:'-0.3rem',
				marginLeft:'auto',
				marginRight:'auto',
			};        
			var aKeyRowStyle={
				//marginBottom:'.3125rem',
				//display:'flex',
			};
			var aKeyCellStyle={
				//padding:'0 .3125rem',
				textAlign:'center',
				//margin:'0 0.5rem',
				verticalAlign:'middle',
				height:'1.4rem',
				border:'0.01rem solid',
				backgroundColor:'#eeeeee',
				minWidth:'1.1em',
				overflow:"hidden",
				//paddingBottom:'0.1rem',
			};
			var aTableLastStyle={
				marginBottom:'-0.275rem',
				position:'relative',
				left:'0.57rem',

			};
			if(this.props.style) Object.assign(tableStyle,this.props.style);
			if(this.props.style) Object.assign(aTableStyle,this.props.style);
			var specialTdStyle=Object.assign({},tdStyle,this.props.specialKeyStyle);
			var specialTdAccentStyle=Object.assign({},tdStyle,this.props.specialKeyAccentStyle);
			var specialAKeyCellStyle=Object.assign({},aKeyCellStyle,this.props.specialKeyStyle);
			var specialAKeyCellAccentStyle=Object.assign({},aKeyCellStyle,this.props.specialKeyAccentStyle);		
			const backSpaceFillColor=this.props.alphaNumeric?(specialAKeyCellAccentStyle.color?specialAKeyCellAccentStyle.color:"#000"):(specialTdAccentStyle.color?specialTdAccentStyle.color:"#000");
			const enterFillColor=this.props.alphaNumeric?(aKeyCellStyle.color?aKeyCellStyle.color:"#000"):(tdStyle.color?tdStyle.color:"#000");
			const upFillColor=this.props.alphaNumeric?(aKeyCellStyle.color?aKeyCellStyle.color:"#000"):(tdStyle.color?tdStyle.color:"#000");
			const downFillColor=this.props.alphaNumeric?(aKeyCellStyle.color?aKeyCellStyle.color:"#000"):(tdStyle.color?tdStyle.color:"#000");
			const backSpaceSvg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24"><g fill="'+backSpaceFillColor+'" transform="scale(0.0234375 0.0234375)"><path d="M896 470v84h-604l152 154-60 60-256-256 256-256 60 60-152 154h604z" /></g></svg>';
			const enterSvg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24"><g fill="'+enterFillColor+'" transform="scale(0.0234375 0.0234375)"><path d="M810 298h86v256h-648l154 154-60 60-256-256 256-256 60 60-154 154h562v-172z" /></g></svg>';
			const upSvg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24"><g fill="'+upFillColor+'" transform="scale(0.0234375 0.0234375)"><path d="M316 658l-60-60 256-256 256 256-60 60-196-196z" /></g></svg>';
			const downSvg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24"><g fill="'+downFillColor+'" transform="scale(0.0234375 0.0234375)"><path d="M316 334l196 196 196-196 60 60-256 256-256-256z" /></g></svg>';
			const backSpaceSvgData="data:image/svg+xml;base64,"+window.btoa(backSpaceSvg);
			const enterSvgData="data:image/svg+xml;base64,"+window.btoa(enterSvg);
			const upSvgData="data:image/svg+xml;base64,"+window.btoa(upSvg);
			const downSvgData="data:image/svg+xml;base64,"+window.btoa(downSvg);
			const backSpaceEl = React.createElement("img",{src:backSpaceSvgData,style:{width:"100%",height:"100%",verticalAlign:"middle"}},null);
			const enterEl = React.createElement("img",{src:enterSvgData,style:{width:"100%",height:"100%"}},null);
			const upEl = React.createElement("img",{src:upSvgData,style:{width:"100%",height:"100%",verticalAlign:"middle"}},null);
			const downEl = React.createElement("img",{src:downSvgData,style:{width:"100%",height:"100%",verticalAlign:"middle"}},null);
			var result;
			if(this.props.simple)
				result=React.createElement("table",{style:tableStyle,key:"1"},
					React.createElement("tbody",{key:"1"},[					  				   					  
					   React.createElement("tr",{key:"3"},[
						   React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:"1",fkey:"7"},'7'),
						   React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:"2",fkey:"8"},'8'),
						   React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:"3",fkey:"9"},'9'),
						   React.createElement(VKTd,{rowSpan:'2',onClickValue:this.props.onClickValue,style:Object.assign({},specialTdAccentStyle,{height:"2rem"}),key:"4",fkey:"backspace"},backSpaceEl)
					   ]),					   
					   React.createElement("tr",{key:"4"},[
						   React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:"1",fkey:"4"},'4'),
						   React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:"2",fkey:"5"},'5'),
						   React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:"3",fkey:"6"},'6'),						   
					   ]),
					   React.createElement("tr",{key:"5"},[
						   React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:"1",fkey:"1"},'1'),
						   React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:"2",fkey:"2"},'2'),
						   React.createElement(VKTd,{style:tdStyle,onClickValue:this.props.onClickValue,key:"3",fkey:"3"},'3'),
						   React.createElement(VKTd,{rowSpan:'2',onClickValue:this.props.onClickValue,style:Object.assign({},specialTdStyle,{height:"90%"}),key:"13",fkey:"enter"},enterEl),
					   ]),
					   React.createElement("tr",{key:"6"},[
						   React.createElement(VKTd,{colSpan:'3',onClickValue:this.props.onClickValue,style:tdStyle,key:"1",fkey:"0"},'0'),
					   ]),
				   ])
				); 
			else
			if(!this.props.alphaNumeric)
				result=React.createElement("table",{style:tableStyle,key:"1"},
					React.createElement("tbody",{key:"1"},[
					   React.createElement("tr",{key:"0"},[
						   React.createElement(VKTd,{colSpan:"2",style:Object.assign({},specialTdAccentStyle,{height:"2rem"}),key:"1",fkey:"Backspace"},backSpaceEl),
						   React.createElement("td",{key:"2"},''),
						   React.createElement(VKTd,{colSpan:"2",style:specialTdAccentStyle,key:"3",onClick:this.switchMode},'ABC...'),
					   ]),					   
					   React.createElement("tr",{key:"1"},[
						   React.createElement(VKTd,{style:specialTdStyle,key:"1",fkey:"F1"},'F1'),
						   React.createElement(VKTd,{style:specialTdStyle,key:"2",fkey:"F2"},'F2'),
						   React.createElement(VKTd,{style:specialTdStyle,key:"3",fkey:"F3"},'F3'),
						   React.createElement(VKTd,{style:specialTdStyle,key:"4",fkey:"F4"},'F4'),
						   React.createElement(VKTd,{style:specialTdStyle,key:"5",fkey:"F5"},'F5'),					   
					   ]),					   
					   React.createElement("tr",{key:"2"},[
						   React.createElement(VKTd,{style:specialTdStyle,key:"1",fkey:"F6"},'F6'),
						   React.createElement(VKTd,{style:specialTdStyle,key:"2",fkey:"F7"},'F7'),
						   React.createElement(VKTd,{style:specialTdStyle,key:"3",fkey:"F8"},'F8'),
						   React.createElement(VKTd,{style:specialTdStyle,key:"4",fkey:"F9"},'F9'),
						   React.createElement(VKTd,{style:specialTdStyle,key:"5",fkey:"F10"},'F10'),					   
					   ]),
					   React.createElement("tr",{key:"2-extras"},[
						   React.createElement(VKTd,{style:specialTdAccentStyle,colSpan:"3",key:"1",fkey:"Tab"},'Tab'),
						   React.createElement(VKTd,{style:tdStyle,key:"2",fkey:"."},'.'),
						   React.createElement(VKTd,{style:tdStyle,key:"3",fkey:"-"},'-'),						   
					   ]),
					   React.createElement("tr",{key:"3"},[
						   React.createElement(VKTd,{style:tdStyle,key:"1",fkey:"7"},'7'),
						   React.createElement(VKTd,{style:tdStyle,key:"2",fkey:"8"},'8'),
						   React.createElement(VKTd,{style:tdStyle,key:"3",fkey:"9"},'9'),
						   React.createElement(VKTd,{colSpan:'2',style:Object.assign({},tdStyle,{height:"2rem",padding:"0px"}),key:"4",fkey:"ArrowUp"},upEl),
					   ]),					   
					   React.createElement("tr",{key:"4"},[
						   React.createElement(VKTd,{style:tdStyle,key:"1",fkey:"4"},'4'),
						   React.createElement(VKTd,{style:tdStyle,key:"2",fkey:"5"},'5'),
						   React.createElement(VKTd,{style:tdStyle,key:"3",fkey:"6"},'6'),
						   React.createElement(VKTd,{colSpan:'2',style:Object.assign({},tdStyle,{height:"2rem",padding:"0px"}),key:"4",fkey:"ArrowDown"},downEl),
					   ]),
					   React.createElement("tr",{key:"5"},[
						   React.createElement(VKTd,{style:tdStyle,key:"1",fkey:"1"},'1'),
						   React.createElement(VKTd,{style:tdStyle,key:"2",fkey:"2"},'2'),
						   React.createElement(VKTd,{style:tdStyle,key:"3",fkey:"3"},'3'),
						   React.createElement(VKTd,{colSpan:'2',rowSpan:'2',style:Object.assign({},specialTdStyle,{height:"90%"}),key:"4",fkey:"Enter"},enterEl),
					   ]),
					   React.createElement("tr",{key:"6"},[
						   React.createElement(VKTd,{colSpan:'3',style:tdStyle,key:"1",fkey:"0"},'0'),
					   ]),
				   ])
				);
			else
				result= React.createElement("div",{key:"1"},[
					React.createElement("table",{style:aTableStyle,key:"1"},
						React.createElement("tbody",{key:"1"},[
							React.createElement("tr",{key:"1"},[
								React.createElement(VKTd,{style:specialAKeyCellStyle,key:"1",fkey:"F1"},'F1'),
								React.createElement(VKTd,{style:specialAKeyCellStyle,key:"2",fkey:"F2"},'F2'),
								React.createElement(VKTd,{style:specialAKeyCellStyle,key:"3",fkey:"F3"},'F3'),
								React.createElement(VKTd,{style:specialAKeyCellStyle,key:"4",fkey:"F4"},'F4'),
							   // React.createElement(VKTd,{style:aKeyCellStyle},'F5'),
								React.createElement(VKTd,{style:specialAKeyCellStyle,key:"5",fkey:"F6"},'F6'),
								React.createElement(VKTd,{style:specialAKeyCellStyle,key:"6",fkey:"F7"},'F7'),
								React.createElement(VKTd,{style:specialAKeyCellStyle,key:"7",fkey:"F8"},'F8'),
								React.createElement(VKTd,{style:specialAKeyCellStyle,key:"8",fkey:"F9"},'F9'),
								React.createElement(VKTd,{style:specialAKeyCellStyle,key:"9",fkey:"F10"},'F10'),
								//React.createElement(VKTd,{onClick:function(){},style:Object.assign({},aKeyCellStyle,{width:'0rem',visibility:'hidden'})},''),
								React.createElement(VKTd,{onClick:this.switchMode,style:specialAKeyCellAccentStyle,key:"10"},'123...'),
							])
						])
					),
					React.createElement("table",{style:aTableStyle,key:"2-extras"},
						React.createElement("tbody",{key:"1"},[
							React.createElement("tr",{key:"1"},[
								React.createElement(VKTd,{style:specialAKeyCellAccentStyle,colSpan:"2",key:"1",fkey:"Tab"},'Tab'),								
								React.createElement(VKTd,{style:aKeyCellStyle,key:"3",fkey:":"},':'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"4",fkey:";"},';'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"5",fkey:"/"},'/'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"6",fkey:"*"},'*'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"7",fkey:"-"},'-'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"8",fkey:"+"},'+'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"9",fkey:","},','),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"10",fkey:"."},'.'),
								React.createElement(VKTd,{style:Object.assign({},specialAKeyCellAccentStyle,{height:"auto","width":"2em"}),bStyle:{width:"50%",fontSize:""},key:"11",fkey:"Backspace"},backSpaceEl),
							]),
						])
					),
					React.createElement("table",{style:aTableStyle,key:"2"},
						React.createElement("tbody",{key:"1"},[
							React.createElement("tr",{key:"1"},[
								React.createElement(VKTd,{style:aKeyCellStyle,key:"1",fkey:"1"},'1'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"2",fkey:"2"},'2'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"3",fkey:"3"},'3'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"4",fkey:"4"},'4'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"5",fkey:"5"},'5'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"6",fkey:"6"},'6'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"7",fkey:"7"},'7'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"8",fkey:"8"},'8'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"9",fkey:"9"},'9'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"10",fkey:"0"},'0'),
							]),
						])
					),
					React.createElement("table",{style:aTableStyle,key:"3"},
						React.createElement("tbody",{key:"1"},[
							React.createElement("tr",{key:"1"},[
								React.createElement(VKTd,{style:aKeyCellStyle,key:"1",fkey:"Q"},'Q'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"2",fkey:"W"},'W'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"3",fkey:"E"},'E'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"4",fkey:"R"},'R'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"5",fkey:"T"},'T'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"6",fkey:"Y"},'Y'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"7",fkey:"U"},'U'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"8",fkey:"I"},'I'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"9",fkey:"O"},'O'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"10",fkey:"P"},'P'),								
							]),
						])
					),
					React.createElement("table",{style:Object.assign({},aTableStyle,{position:'relative',left:'0.18rem'}),key:"4"},
						React.createElement("tbody",{key:"1"},[
							React.createElement("tr",{key:"1"},[
								React.createElement(VKTd,{style:aKeyCellStyle,key:"1",fkey:"A"},'A'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"2",fkey:"S"},'S'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"3",fkey:"D"},'D'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"4",fkey:"F"},'F'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"5",fkey:"G"},'G'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"6",fkey:"H"},'H'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"7",fkey:"J"},'J'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"8",fkey:"K"},'K'),
								React.createElement(VKTd,{style:aKeyCellStyle,key:"9",fkey:"L"},'L'),
								React.createElement(VKTd,{style:Object.assign({},specialAKeyCellStyle,{height:"auto"}),rowSpan:"2",key:"10",fkey:"Enter"},enterEl),
							]),
							React.createElement("tr",{key:"2"},[
								React.createElement("td",{style:Object.assign({},aKeyCellStyle,{backgroundColor:'transparent',border:'none'}),colSpan:"9",key:"1"},[
									React.createElement("table",{style:Object.assign({},aTableStyle,aTableLastStyle),key:"1"},
										React.createElement("tbody",{key:"1"},[
											React.createElement("tr",{key:"1"},[
												React.createElement(VKTd,{style:aKeyCellStyle,key:"1",fkey:"Z"},'Z'),
												React.createElement(VKTd,{style:aKeyCellStyle,key:"2",fkey:"X"},'X'),
												React.createElement(VKTd,{style:aKeyCellStyle,key:"3",fkey:"C"},'C'),
												React.createElement(VKTd,{style:aKeyCellStyle,key:"4",fkey:"V"},'V'),
												React.createElement(VKTd,{style:aKeyCellStyle,key:"5",fkey:"B"},'B'),
												React.createElement(VKTd,{style:aKeyCellStyle,key:"6",fkey:"N"},'N'),
												React.createElement(VKTd,{style:aKeyCellStyle,key:"7",fkey:"M"},'M'),
												React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{minWidth:'2rem',height:"auto"}),key:"8",fkey:"ArrowUp"},upEl),
											]),
											React.createElement("tr",{key:"2"},[
												React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{visibility:"hidden"}),colSpan:"1",key:"1"},''),
												React.createElement(VKTd,{style:aKeyCellStyle,colSpan:"5",key:"2",fkey:" "},'SPACE'),
												React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{visibility:"hidden"}),colSpan:"1",key:"3"},''),
												React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{minWidth:'2rem',height:"auto"}),key:"4",fkey:"ArrowDown"},downEl),
											]),
										])
									),
								]),
							]),
						])
					),

				]);
			return result;
		},
	});	

	const TableElement = React.createClass({
		render:function(){
			var tableStyle={
				borderCollapse:'separate',
				borderSpacing:'0px',
				width:'100%',
			};
			if(this.props.style)
				Object.assign(tableStyle.this.props.style);
			return React.createElement("table",{style:tableStyle},this.props.children);
		}	
	});
	const THeadElement = React.createClass({
		render:function(){
			var theadStyle={};
			if(this.props.style)
				Object.assign(theadStyle.this.props.style);
			return React.createElement("thead",{style:theadStyle},this.props.children);
		}	
	});
	const TBodyElement = React.createClass({
		render:function(){
			var tbodyStyle={};
			if(this.props.style)
				Object.assign(tbodyStyle.this.props.style);
			return React.createElement("tbody",{style:tbodyStyle},this.props.children);
		}	
	});
	const THElement = React.createClass({
		render:function(){
			var bColor='#b6b6b6';
			var thStyle={
				backgroundColor:'#eeeeee',
				borderBottom:'1px solid '+bColor,
				borderLeft:'none',
				borderRight:'1px solid '+bColor,
				borderTop:'1px solid '+bColor,
				fontWeight:'bold',
				padding:'1px 2px 1px 2px',
				verticalAlign:'middle',
			};        
			if(this.props.style)
				Object.assign(thStyle,this.props.style);
			return React.createElement("th",{style:thStyle},this.props.children);
		}	
	});
	const TDElement = React.createClass({
		render:function(){
			var tdStyle={};
			var bColor='#b6b6b6';
			var thStyle={
				backgroundColor:'#eeeeee',
				borderBottom:'1px solid '+bColor,
				borderLeft:'none',
				borderRight:'1px solid '+bColor,
				borderTop:'1px solid '+bColor,
				fontWeight:'bold',
				padding:'0.5rem 1rem',
				verticalAlign:'middle',
				fontSize:'1.7rem',
			};
			var tdStyleOdd=Object.assign({},thStyle,{
				borderBottom:'none',
				fontWeight:'normal',			
				backgroundColor:'#fafafa',
			});
			var tdStyleEven=Object.assign({},thStyle,{
				borderBottom:'none',
				fontWeight:'normal',
				backgroundColor:'#ffffff',
			});
			if(this.props.odd)
				Object.assign(tdStyle,tdStyleOdd);
			else
				Object.assign(tdStyle,tdStyleEven);
			if(this.props.style)
				Object.assign(tdStyle,this.props.style);
			return React.createElement("td",{style:tdStyle},this.props.children);
		}	
	});
	const TRElement = React.createClass({
		getInitialState:function(){
			return {touch:false};
		},
		onClick:function(e){
			if(this.props.onClick)
				this.props.onClick(e)
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
		render:function(){
			var trStyle={
				outline:this.state.touch?'0.1rem solid blue':'none',
				outlineOffset:'-0.1rem',
			};
			if(this.props.style)
				Object.assign(trStyle.this.props.style);
			return React.createElement("tr",{style:trStyle,onClick:this.onClick,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children);
		}	
	});
	
	const InputElement = React.createClass({
		onChange:function(e){
			if(this.props.onChange&&!this.props.readOnly)
				this.props.onChange(e);
		},
		onBlur:function(e){
			if(this.props.onBlur&&!this.props.readOnly)
				this.props.onBlur(e)
		},
		render:function(){
			var labelStyle={
				color:"rgb(33,33,33)",
			};
			var contStyle={
				width:"100%",				
				padding:"0.4rem 0.3125rem",
				boxSizing:"border-box",
			};
			var inpContStyle={
				display:"flex",
				height:"auto",
				lineHeight:"1",
				margin:"0.124rem 0rem",
				position:"relative",
				verticalAlign:"middle",
				width:"100%",
			};
			var inp2ContStyle={
				flex:"1 1 0%",
				height:"auto",
				minHeight:"100%",
				overflow:"hidden",				
			};
			var inputStyle={
				textOverflow:"ellipsis",
				margin:"0rem",
				verticalAlign:"top",
				color:"rgb(33,33,33)",
				border:"0.01rem solid rgb(182, 182, 182)",
				height:"auto",
				padding:"0.2172rem 0.3125rem 0.2172rem 0.3125rem",
				width:"100%",
				zIndex:"0",
				boxSizing:"border-box",
				MozAppearence:"none",
				whiteSpace:"nowrap",
				overflow:"hidden",
				fontSize:"inherit",
				backgroundColor:this.props.readOnly?"#eeeeee":"",
			};
			if(this.props.inputStyle)
				Object.assign(inputStyle,this.props.inputStyle);						
			const labelEl = this.props.label?React.createElement("label",{key:"1",style:labelStyle},this.props.label):null;
			if(this.props.style)
				Object.assign(contStyle,this.props.style);
			return React.createElement("div",{style:contStyle},[
				labelEl,
				React.createElement("div",{key:"2",style:inpContStyle},
					React.createElement("div",{key:"1",style:inp2ContStyle},
						React.createElement("input",{key:"1",style:inputStyle,onChange:this.onChange,onBlur:this.onBlur,value:this.props.value},null)
					)
				)
			]);			
		},
	});
	const DropDownElement = React.createClass({
		onChange:function(e){
			if(this.props.onChange)
				this.props.onChange(e);
		},
		onClick:function(e){
			if(this.props.onClick)
				this.props.onClick(e);
		},
		render:function(){
			var labelStyle={
				color:"rgb(33,33,33)",
			};
			var contStyle={
				width:"100%",				
				padding:"0.4rem 0.3125rem",
				boxSizing:"border-box",
			};
			var inpContStyle={
				display:"flex",
				height:"auto",
				lineHeight:"1",
				margin:"0.124rem 0rem",
				position:"relative",
				verticalAlign:"middle",
				width:"100%",
			};
			var inp2ContStyle={
				flex:"1 1 0%",
				height:"auto",
				minHeight:"100%",
				overflow:"hidden",				
			};
			var inputStyle={
				textOverflow:"ellipsis",
				margin:"0rem",
				verticalAlign:"top",
				color:"rgb(33,33,33)",
				border:"0.01rem solid rgb(182, 182, 182)",
				height:"auto",
				padding:"0.2172rem 0.3125rem 0.2172rem 0.3125rem",
				width:"100%",
				zIndex:"0",
				boxSizing:"border-box",
				MozAppearence:"none",
				whiteSpace:"nowrap",
				overflow:"hidden",
				fontSize:"inherit",				
			};
			const popupStyle={
				position:"absolute",
				border: "0.02rem solid #ccc",
				minWidth: "100%",
				overflow: "auto",				
				maxHeight: "10rem",				
				backgroundColor: "white",
				zIndex: "5"				
			};
			const openButtonStyle={
				minHeight:"",
				height:"100%",
				padding:"0rem",
			};
			const openButtonWrapperStyle= Object.assign({},inp2ContStyle,{
				flex:"0 1 auto"
			});
			const buttonImageStyle={
				width:"1rem",
				verticalAlign:"middle",
				display:"inline",
				height:"100%",
			};
			if(this.props.inputStyle)
				Object.assign(inputStyle,this.props.inputStyle);
			if(this.props.style)
				Object.assign(contStyle,this.props.style);
			const svg ='<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" width="446.25px" height="446.25px" viewBox="0 0 446.25 446.25" style="fill: rgb(0, 0, 0);" xml:space="preserve"><path d="M318.75,280.5h-20.4l-7.649-7.65c25.5-28.05,40.8-66.3,40.8-107.1C331.5,73.95,257.55,0,165.75,0S0,73.95,0,165.75 S73.95,331.5,165.75,331.5c40.8,0,79.05-15.3,107.1-40.8l7.65,7.649v20.4L408,446.25L446.25,408L318.75,280.5z M165.75,280.5 C102,280.5,51,229.5,51,165.75S102,51,165.75,51S280.5,102,280.5,165.75S229.5,280.5,165.75,280.5z" style="fill: rgb(0, 0, 0);"></path></svg>';
			const svgData="data:image/svg+xml;base64,"+window.btoa(svg);
			const urlData = this.props.url?this.props.url:svgData;
			const buttonImage = React.createElement("img",{key:"buttonImg",src:urlData,style:buttonImageStyle},null);
			const labelEl=this.props.label?React.createElement("label",{key:"1",style:labelStyle},this.props.label):null;
			const popupWrapEl=this.props.open?React.createElement("div",{key:"popup",style:popupStyle},this.props.children):null;
			return React.createElement("div",{style:contStyle},[
				labelEl,
				React.createElement("div",{key:"2",style:inpContStyle},[
					React.createElement("div",{key:"1",style:inp2ContStyle},[
						React.createElement("input",{key:"1",style:inputStyle,onChange:this.onChange,onBlur:this.onBlur,value:this.props.value},null),
						popupWrapEl					
					]),
					React.createElement("div",{key:"2",style:openButtonWrapperStyle},
						React.createElement(GotoButton,{key:"1",style:openButtonStyle,onClick:this.onClick},buttonImage)
					)
				])
			]);			
		},
	});
	
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
			if(!this.focus){
				this.reportChange("blur");							
			}
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
			
		},		
		componentWillUnmount:function(){
			if(!this.el) return;
			this.timeout=null;
			this.el.removeEventListener("focus",this.onFocus);
			this.el.removeEventListener("blur",this.onBlur);
		},
		render:function(){
			var style={
				display:"inline-block",
			};
			if(this.props.style)
				Object.assign(style,this.props.style);
			return React.createElement("div",{ref:ref=>this.el=ref,style:style,tabIndex:"0"},this.props.children);
		}
	});
	const PopupElement = React.createClass({
		render:function(){
			var style={
				position:"fixed",
				zIndex:"6",
				border:"0.02rem solid #eee",
				backgroundColor:"white",
			};
			if(this.props.style)
				Object.assign(style,this.props.style);
			return React.createElement("div",{style:style},this.props.children);
		}		
	});
	const Checkbox = React.createClass({
		onClick:function(e){
			if(this.props.onClick)
				this.props.onClick(e);
		},
		render:function(){
			var contStyle={
				flexGrow:"0",
				minHeight:"2.8125rem",
				position:"relative",
				maxWidth:"100%",
				padding:"0.4rem 0.3125rem",
				flexShrink:"1",
				boxSizing:"border-box",
				lineHeight:"1",
				
			};
			var cont2Style={
				border:"none",
				display:"inline-block",
				lineHeight:"100%",
				margin:"0rem",
				marginBottom:"0.55rem",
				outline:"none",
				position:"absolute",
				whiteSpace:"nowrap",
				width:"calc(100% - 1rem)",
				cursor:"pointer",
				bottom:"0rem",
			};
			var checkBoxStyle={
				border:"0.02rem #b6b6b6 solid",
				color:"#212121",
				display:"inline-block",
				height:"1.625rem",
				lineHeight:"100%",
				margin:"0rem 0.02rem 0rem 0rem",
				padding:"0rem",
				position:"relative",
				verticalAlign:"middle",
				width:"1.625rem",
				boxSizing:"border-box",
				backgroundColor:this.props.readOnly?"#eeeeee":"white",
			};
			var labelStyle={
				maxWidth:"calc(100% - 2.165rem)",
				padding:"0rem 0.3125rem",
				verticalAlign:"middle",
				cursor:"pointer",
				display:"inline-block",
				lineHeight:"1.3",
				overflow:"hidden",
				textOverflow:"ellipsis",
				whiteSpace:"nowrap",
				boxSizing:"border-box",
			};
			const imageStyle = {
				position:"absolute",
				bottom:"0rem",
				height:"90%",
				width:"100%",
			};
			if(this.props.style)
				Object.assign(contStyle,this.props.style);
			if(this.props.innerStyle)
				Object.assign(cont2Style,this.props.innerStyle);
			if(this.props.checkBoxStyle)
				Object.assign(checkBoxStyle,this.props.checkBoxStyle);
			if(this.props.labelStyle)
				Object.assign(labelStyle,this.props.labelStyle);
			const svg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="16px" viewBox="0 0 128.411 128.411"><polygon points="127.526,15.294 45.665,78.216 0.863,42.861 0,59.255 44.479,113.117 128.411,31.666"/></svg>';
			const svgData="data:image/svg+xml;base64,"+window.btoa(svg);			
			const checkImage = this.props.on?React.createElement("img",{style:imageStyle,src:svgData,key:"checkImage"},null):null
			
			return React.createElement("div",{style:contStyle},
				React.createElement("span",{style:cont2Style,key:"1",onClick:this.onClick},[
					React.createElement("span",{style:checkBoxStyle,key:"1"},checkImage),
					React.createElement("label",{style:labelStyle,key:"2"},this.props.label)
				])
			);
		}
	});
	const sendVk = ctx => (event,value) => {sender.send(ctx,"click",value);}
	const onClickValue=({sendVk})
	
	const transforms= {
		tp:{
		DocElement,FlexContainer,FlexElement,GotoButton,CommonButton, TabSet, GrContainer, FlexGroup, VirtualKeyboard,
		InputElement,DropDownElement,Chip,FocusableElement,PopupElement,Checkbox,
		MenuBarElement,MenuDropdownElement,FolderMenuElement,ExecutableMenuElement,
		TableElement,THeadElement,TBodyElement,THElement,TRElement,TDElement,
		},
		onClickValue,
	};
	const receivers = {};
	return {transforms,receivers};
}
export default MetroUi