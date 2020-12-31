import React from 'react'
const { createElement: $ } = React
import ReactDOM from 'react-dom'
import VdomApp from './vdom-app.js'
/*
if webpack is running 
import '../../src/styles/style.scss'
*/
//start here

const containerElement = document.createElement("div")
document.body.appendChild(containerElement)
ReactDOM.render($(VdomApp),containerElement); 