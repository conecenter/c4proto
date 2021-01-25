"use strict";

const http = require('http');

const locks = {}
const lock = (path,owner,period) => {
  const now = Date.now()
  const wasLock = locks[path]
  if(owner && period && (!wasLock || wasLock.owner === owner || wasLock.until < now))
    return locks[path] = { owner, until: now+period }
}

const server = http.createServer((req, res) => {
  const owner = req.headers["x-r-lock-owner"]
  const period = parseInt(req.headers["x-r-lock-period"])
  res.statusCode = req.method === "POST" && lock(req.path,owner,period) ? 200 : 400
  res.end()
})

server.listen(process.env.C4HTTP_PORT, "0.0.0.0", () => {
  console.log("Server running",server.address());
})

// curl -v kc-elector2-dev-1.kc-elector2-dev:1080 -v -XPOST -H 'x-r-lock-owner: he' -H 'x-r-lock-period: 5000'
