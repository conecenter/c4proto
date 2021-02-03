"use strict";

const http = require('http');

const locks = {}
const lock = (url,owner,period) => {
  const now = Date.now()
  const wasLock = locks[url]
  if(owner && period && (!wasLock || wasLock.owner === owner || wasLock.until < now)){
    if(!wasLock || wasLock.owner !== owner) console.log(`${url} locked by ${owner}`)
    locks[url] = { owner, until: now+period }
    return true
  }
}

const server = http.createServer((req, res) => {
  const owner = req.headers["x-r-lock-owner"]
  const period = parseInt(req.headers["x-r-lock-period"])
  res.statusCode = req.method === "POST" && lock(req.url,owner,period) ? 200 : 400
  res.end()
})

server.listen(process.env.C4HTTP_PORT, "0.0.0.0", () => {
  console.log("Server running",server.address());
})

// curl -v kc-elector2-dev-1.kc-elector2-dev:1080 -v -XPOST -H 'x-r-lock-owner: he' -H 'x-r-lock-period: 5000'
