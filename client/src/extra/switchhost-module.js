"use strict";
import React from 'react'

export default function SwitchHost(log,window){	
	const switchHost = () =>{
		window.changeHost&&window.changeHost()
	}
	const receivers = {		
		switchHost
	};	
	return {receivers};
}