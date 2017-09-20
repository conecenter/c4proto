
/*wifiNetworksRSSI(function(err, data, raw) {
  if (!err) {
    console.log(data); // formatted data with SSID, BSSID, RSSI
    // console.log(raw); // raw output from netsh
  } else {
    console.log(err);
  }
});
*/
export default function WinWifi(log,require,process,setInterval){
	let nodejs = false
	const wifiCallbacks = [];
	let interval = null
	const regWifi = (callback) => {
		if(!nodejs) return null
		wifiCallbacks.push(callback)
		if(!interval) interval = setInterval(wifiNetworksRSSI,5000)
		const unreg = () =>	{
			const index = wifiCallbacks.findIndex(wc=>wc == callback)
			wifiCallbacks.splice(index,1)
		}
		return {unreg}
	}
	if(typeof require != "function") return {regWifi}
	const spawn = require('child_process').spawn;
	const systemRoot = process.env.SystemRoot || 'C:\\Windows';
	const tool       = systemRoot + '\\System32\\netsh.exe';	
	const fs      = require('fs');
	let init = false
	nodejs = true
	fs.stat(tool, function (err, stats) {
		if (stats) init = true
	})
	function wifiNetworksRSSI() {
	  // prepare result string of data
	  if(!init) return;
	  let res = '';
	  // spawn netsh with required settings
	  const netsh = spawn('netsh', ['wlan', 'show', 'interfaces']);

	  // get data and append to main result
	  netsh.stdout.on('data', function (data) {
		res += data;
	  });

	  // if error occurs
	  netsh.stderr.on('data', function (data) {
		log('stderr: ' + data);
	  });

	  // when done
	  netsh.on('close', function (code) {
		if (code == 0) { // normal exit
		  // prepare array for formatted data		 
		  // split response to blocks based on double new line
		 const raw = res.split('\r\n\r\n');
		  let network = {};		  
		  raw.forEach(b=>{					  
			  // parse RSSI (Signal Strength)
			if(network.rssi) return;  
			const match = b.match(/\s+Signal\s+: ([0-9]+)%/);
			if (match && match.length == 2) {
				network.rssi = parseInt(match[1]);
				network.plain = b;
			} else {
				network.rssi = null;
			}			
		  })
		  // callback with networks and raw data
		  let level = 0
		  if(network.rssi){
			  if(network.rssi>=1 && network.rssi <=25) level = 1
			  if(network.rssi>=26 && network.rssi <=50) level = 2
			  if(network.rssi>=51 && network.rssi <=75) level = 3
			  if(network.rssi>=76 && network.rssi <=100) level = 4
		  }			  
		  wifiCallbacks.forEach(c=>c(level,network.plain))
		  //fn(null, networks, res);
		} else {
		  // if exit was not normal, then throw error
		  //fn(code);
		}
	  });
	}
	
	return {regWifi}	
}
