"use strict";
import React from 'react'

function MetroUi(){
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
			return React.createElement(button,{style:selStyle,onMouseOver:this.mouseOver,onMouseOut:this.mouseOut,onClick:this.props.onClick,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children);
		}
	});
	const MenuItem=React.createClass({
		getInitialState:function(){
			return {mouseOver:false,touch:false};
		},
		mouseOver:function(e){
			this.setState({mouseOver:true});
			if(this.props.OnClick)
				this.props.OnClick(e);
		},
		mouseOut:function(e){
			this.setState({mouseOver:false});
			if(this.props.OnClick)
				this.props.OnClick(e);
		},
		render:function(){		
			var selStyle={

			};        
			
			if(this.props.style)
				Object.assign(selStyle,this.props.style);
			if(this.state.mouseOver)
				Object.assign(selStyle,this.props.overStyle);		
			return React.createElement("div",{style:selStyle,onMouseOver:this.mouseOver,onMouseOut:this.mouseOut,onClick:this.props.onClick,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children);
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

			if(this.props.fontSize)
				document.documentElement.style.fontSize=this.props.fontSize;
			if(this.props.fontFamily)
				document.documentElement.style.fontFamily=this.props.fontFamily;
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

			return React.createElement('input',{style:newStyle,readOnly:'readonly',value:this.props.children},null);
		}
	});
	const StatusElement=React.createClass({
		getInitialState:function(){
			return {lit:false};
		},
		signal:function(on){
			if(on) this.setState({lit:true});
			else this.setState({lit:false});
		},
		componentDidMount:function(){
			if(window.CustomMeasurer) CustomMeasurer.regCallback(this.props.fkey.toLowerCase(),this.signal);
		},
		componentWillUnmount:function(){
			if(window.CustomMeasurer) CustomMeasurer.unregCallback(this.props.fkey.toLowerCase());
		},    
		render:function(){
			var newStyle={
				marginTop:'.6125rem',
			};
		
			return React.createElement(Chip,{style:newStyle,on:this.state.lit},this.props.fkey);
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
				var event=new KeyboardEvent("keypress",{key:this.props.fkey})
				window.dispatchEvent(event);
			}
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
			if(this.props.onClick)
				this.props.onClick(e);
		},
		render:function(){
			var tableStyle={
				fontSize:'2.2rem',
				borderSpacing:'0.5rem',
				marginTop:'-0.5rem',
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
				//paddingBottom:'0.1rem',
			};
			var aTableLastStyle={
				marginBottom:'-0.275rem',
				position:'relative',
				left:'0.57rem',

			};
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
			const backSpaceEl = React.createElement("img",{src:backSpaceSvgData,style:{width:"100%",height:"100%"}},null);
			const enterEl = React.createElement("img",{src:enterSvgData,style:{width:"100%",height:"100%"}},null);
			const upEl = React.createElement("img",{src:upSvgData,style:{width:"100%",height:"100%"}},null);
			const downEl = React.createElement("img",{src:downSvgData,style:{width:"100%",height:"100%"}},null);
			var result;
			if(!this.props.alphaNumeric)
				result=React.createElement("table",{style:tableStyle,key:"1"},
					React.createElement("tbody",{key:"1"},[
					   React.createElement("tr",{key:"0"},[
						   React.createElement(VKTd,{colSpan:"2",style:Object.assign({},specialTdAccentStyle,{height:"auto"}),key:"1",fkey:"<-"},backSpaceEl),
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
					   React.createElement("tr",{key:"3"},[
						   React.createElement(VKTd,{style:tdStyle,key:"1",fkey:"7"},'7'),
						   React.createElement(VKTd,{style:tdStyle,key:"2",fkey:"8"},'8'),
						   React.createElement(VKTd,{style:tdStyle,key:"3",fkey:"9"},'9'),
						   React.createElement(VKTd,{colSpan:'2',style:Object.assign({},tdStyle,{height:"auto"}),key:"4",fkey:"^"},upEl),
					   ]),
					   React.createElement("tr",{key:"4"},[
						   React.createElement(VKTd,{style:tdStyle,key:"1",fkey:"4"},'4'),
						   React.createElement(VKTd,{style:tdStyle,key:"2",fkey:"5"},'5'),
						   React.createElement(VKTd,{style:tdStyle,key:"3",fkey:"6"},'6'),
						   React.createElement(VKTd,{colSpan:'2',style:Object.assign({},tdStyle,{height:"auto"}),key:"4",fkey:"v"},downEl),
					   ]),
					   React.createElement("tr",{key:"5"},[
						   React.createElement(VKTd,{style:tdStyle,key:"1",fkey:"1"},'1'),
						   React.createElement(VKTd,{style:tdStyle,key:"2",fkey:"2"},'2'),
						   React.createElement(VKTd,{style:tdStyle,key:"3",fkey:"3"},'3'),
						   React.createElement(VKTd,{colSpan:'2',rowSpan:'2',style:Object.assign({},tdStyle,{height:"auto"}),key:"4",fkey:"enter"},enterEl),
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
								React.createElement(VKTd,{style:Object.assign({},specialAKeyCellAccentStyle,{height:"auto"}),key:"11",fkey:"<-"},backSpaceEl),
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
								React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{height:"auto"}),rowSpan:"2",key:"10",fkey:"enter"},enterEl),
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
												React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{minWidth:'2rem',height:"auto"}),key:"8",fkey:"^"},upEl),
											]),
											React.createElement("tr",{key:"2"},[
												React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{visibility:"hidden"}),colSpan:"1",key:"1"},''),
												React.createElement(VKTd,{style:aKeyCellStyle,colSpan:"5",key:"2",fkey:"SPACE"},'SPACE'),
												React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{visibility:"hidden"}),colSpan:"1",key:"3"},''),
												React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{minWidth:'2rem',height:"auto"}),key:"4",fkey:"v"},downEl),
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
	const TerminalElement=React.createClass({   
		componentDidMount:function(){
			if(window.CustomTerminal)
				CustomTerminal.init(this.props.host,this.props.port,this.props.username,this.props.password,(this.props.version||0));
		},
		componentWillUnmount:function(){
			if(window.CustomTerminal)
				CustomTerminal.destroy();			
		},
		componentDidUpdate:function(prevProps, prevState){
			if(window.CustomTerminal){
				CustomTerminal.destroy(this.props.version);				
				CustomTerminal.init(this.props.host,this.props.port,this.props.username,this.props.password,this.props.version);
				console.log("reinit term");
			}
		},
		render:function(){
			var style={
				fontSize:'0.8rem',
			};
			if(this.props.style)
				Object.assign(style,this.props.style);
			return React.createElement("div",{id:'terminal',version:this.props.version,style:style},null);
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
	const MJobCell = React.createClass({
		getInitialState:function(){
			return ({data:null,version:0});
		},
		signal:function(data){
			//const gData=(data!=undefined&&parseInt(data)>=0?data:null);
			const gData=(data?data:null);			
			this.setState({data:gData});		
		},
		componentDidMount:function(){
			if(window.CustomMeasurer)
				CustomMeasurer.regCallback(this.props.fkey,this.signal);        
		},
		componentWillUnmount:function(){
			if(window.CustomMeasurer)
				CustomMeasurer.unregCallback(this.props.fkey);
		},
		onChange:function(e){
			if(this.props.onChange)
				this.props.onChange(e);
		},
		componentWillReceiveProps:function(nextProps){
			if(nextProps.init&&nextProps.init!=this.props.init)
				this.setState({data:null});
		},
		onClick:function(e){
			if(this.props.onClick)
				this.props.onClick(e);
		},
		render:function(){
			var style={
				minWidth:'2rem',
			};
			const inpStyle={
				border:'none',
				fontSize:'1.7rem',
				width:'100%',
				backgroundColor:'inherit',
				padding:'0px',
				margin:'0px',
				flexBasis:'7rem',
				flexGrow:'1',
			};
			Object.assign(style,this.props.style);		
			const statusText = (this.props.statusText?this.props.statusText:"");
			
			return React.createElement(TDElement,{key:"wEl",odd:this.props.odd,style},[
				React.createElement(ControlledComparator,{key:"1",onChange:this.onChange,data:this.state.data},null),
				React.createElement('div',{key:"2",style:{display:'flex',flexWrap:'noWrap'}},[				
					React.createElement("input",{type:"text",readOnly:"readonly",key:"3",style:inpStyle,value:statusText},null),
					(this.props.time&&this.props.time.length>0?
					React.createElement("span",{style:{key:"time",marginRight:"1rem",lineHeight:"1"}},this.props.time):null),
					//(this.state.data!=null?
					//React.createElement(GotoButton,{key:"2",onClick:this.onClick,style:this.props.buttonStyle,overStyle:this.props.buttonOverStyle},buttonText):null),
				]),
			]);
		},
	});

	const ControlledComparator = React.createClass({
		componentDidUpdate:function(prevP,prevS){
			if(this.props.onChange&&this.props.data&&prevP.data!==this.props.data){			
				const e={target:{value:this.props.data}};
				console.log("change w");
				this.props.onChange(e);
			}
		},
		render:function(){		
			//const value = this.props.data!=null?this.props.data:"";
			return React.createElement('span',{key:"1"},null);
		},
	});
	const IconCheck = React.createClass({
		render:function(){
			var style={};
			Object.assign(style,this.props.style);
			const imageSvg ='<svg version="1.1"  xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 30 30"><g><path fill="#3C763D" d="M22.553,7.684c-4.756,3.671-8.641,7.934-11.881,12.924c-0.938-1.259-1.843-2.539-2.837-3.756 C6.433,15.13,4.027,17.592,5.419,19.3c1.465,1.795,2.737,3.734,4.202,5.529c0.717,0.88,2.161,0.538,2.685-0.35 c3.175-5.379,7.04-9.999,11.973-13.806C26.007,9.339,24.307,6.33,22.553,7.684z"/></g></svg>';
			const imageSvgData = "data:image/svg+xml;base64,"+window.btoa(imageSvg);
			return React.createElement("img",{style,src:imageSvgData},null);
		},
	});
	
	const PingReceiver = function(){
		let pingerTimeout=null;
		let callback=null;
		function ping(){			
			if(pingerTimeout){clearTimeout(pingerTimeout); pingerTimeout = null;}
			if(!callback) return;
			pingerTimeout=setTimeout(function(){if(callback) callback(false);},10000);
			callback(true);
		};		
		function regCallback(func){
			callback=func;
		};
		function unregCallback(){
			callback=null;
		};
		return {ping,regCallback,unregCallback};
	}();
	const ConnectionState = React.createClass({
		getInitialState:function(){
			return {on:false};
		},
		signal:function(on){			
			if(this.state.on!=on)
				this.setState({on});
		},
		componentDidMount:function(){					
			if(PingReceiver)
				PingReceiver.regCallback(this.signal);
		},
		componentWillUnmount:function(){
			if(PingReceiver)
				PingReceiver.unregCallback();
		},
		render:function(){
			var style={
				borderRadius:"1rem",
				border:"0.1rem solid black",
				backgroundColor:this.state.on?"green":"red",		
				display:'inline-block',
			};
			var iconStyle={
				position:'relative',
				top:'-10%',
			};
			Object.assign(style,this.props.style);
			Object.assign(iconStyle,this.props.iconStyle);
			const imageSvg='<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 285.269 285.269" style="enable-background:new 0 0 285.269 285.269;" xml:space="preserve"> <path style="fill:black;" d="M272.867,198.634h-38.246c-0.333,0-0.659,0.083-0.986,0.108c-1.298-5.808-6.486-10.108-12.679-10.108 h-68.369c-7.168,0-13.318,5.589-13.318,12.757v19.243H61.553C44.154,220.634,30,206.66,30,189.262 c0-17.398,14.154-31.464,31.545-31.464l130.218,0.112c33.941,0,61.554-27.697,61.554-61.637s-27.613-61.638-61.554-61.638h-44.494 V14.67c0-7.168-5.483-13.035-12.651-13.035h-68.37c-6.193,0-11.381,4.3-12.679,10.108c-0.326-0.025-0.653-0.108-0.985-0.108H14.336 c-7.168,0-13.067,5.982-13.067,13.15v48.978c0,7.168,5.899,12.872,13.067,12.872h38.247c0.333,0,0.659-0.083,0.985-0.107 c1.298,5.808,6.486,10.107,12.679,10.107h68.37c7.168,0,12.651-5.589,12.651-12.757V64.634h44.494 c17.398,0,31.554,14.262,31.554,31.661c0,17.398-14.155,31.606-31.546,31.606l-130.218-0.04C27.612,127.862,0,155.308,0,189.248 s27.612,61.386,61.553,61.386h77.716v19.965c0,7.168,6.15,13.035,13.318,13.035h68.369c6.193,0,11.381-4.3,12.679-10.108 c0.327,0.025,0.653,0.108,0.986,0.108h38.246c7.168,0,12.401-5.982,12.401-13.15v-48.977 C285.269,204.338,280.035,198.634,272.867,198.634z M43.269,71.634h-24v-15h24V71.634z M43.269,41.634h-24v-15h24V41.634z M267.269,258.634h-24v-15h24V258.634z M267.269,228.634h-24v-15h24V228.634z"/></svg>';
			const imageSvgData = "data:image/svg+xml;base64,"+window.btoa(imageSvg);
			
			return React.createElement("div",{style:style},
				React.createElement("img",{style:iconStyle,src:imageSvgData},null)
				);
			
		},
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
				backgroundColor:this.props.readOnly?"#eeeeee":"transparent",
			};
			if(this.props.inputStyle)
				Object.assign(inputStyle,this.props.inputStyle);
			var inputProps = {key:"1",style:inputStyle,onChange:this.onChange,onBlur:this.onBlur,value:this.props.value};
			if(this.props.readOnly)
				Object.assign(inputProps,{readOnly:"readOnly"});
			return React.createElement("div",{style:contStyle},[
				React.createElement("label",{key:"1",style:labelStyle},this.props.label),
				React.createElement("div",{key:"2",style:inpContStyle},
					React.createElement("div",{key:"1",style:inp2ContStyle},
						React.createElement("input",inputProps,null)
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
				width: "100%",
				overflow: "auto",
				maxHeight: "10rem",				
				backgroundColor: "white",
				zIndex: "1"				
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
			};
			if(this.props.inputStyle)
				Object.assign(inputStyle,this.props.inputStyle);			
			const buttonImage = this.props.url?React.createElement("img",{key:"buttonImg",src:this.props.url,style:buttonImageStyle},null):"A";
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
	const transforms= {
		tp:{
		DocElement,FlexContainer,FlexElement,GotoButton,CommonButton, TabSet, GrContainer, FlexGroup, StatusElement, VirtualKeyboard, TerminalElement,MJobCell,IconCheck,ConnectionState,
		InputElement,DropDownElement,
		TableElement,THeadElement,TBodyElement,THElement,TRElement,TDElement,		
		},
	};
	const receivers = {
		ping:PingReceiver.ping
	};
	return {transforms,receivers};
}
export default MetroUi