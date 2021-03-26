"use strict";

const http = require('http');
const { spawn } = require('child_process');

const touch = async () => {
  const proc = spawn("sh",["-c",process.env.C4CI_CMD])
  proc.stdout.on('data', data=>console.log(data.toString()))
  proc.stderr.on('data', data=>console.error(data.toString()))
  const done = new Promise((resolve, reject) => proc.on('close', resolve))
  const code = await done
  return code === 0
}

const read = req => new Promise((resolve, reject) => {
    let res = ''
    req.on('data', chunk => { res += chunk })
    req.on('end', () => resolve(res))
})

const storage = {}
const put = (k,v) => storage[k] = v

const server = http.createServer(async (req, res) => {
    try {
        const [status,content] =
            req.method === "POST" && req.url === "/touch" ? [(await touch()) ? 200:500, ""] :
            req.method === "PUT" && req.url && req.url.startsWith("/tmp/") ? [(put(req.url, await read(req)),200), ""] :
            req.method === "GET" && req.url && req.url.startsWith("/tmp/") && storage[req.url] ? [200,storage[req.url]] :
            [400,""]
        res.statusCode = status
        res.end(content)
    } catch(e) {
        res.statusCode = 500
        res.end()
        console.log(e)
    }
})

server.listen(process.env.C4HTTP_PORT, "0.0.0.0", () => {
  console.log("Server running",server.address());
})