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

const server = http.createServer(async (req, res) => {
  res.statusCode = req.method === "POST" && req.url === "/touch" ? ((await touch()) ? 200:500) : 400
  res.end()
})

server.listen(process.env.C4HTTP_PORT, "0.0.0.0", () => {
  console.log("Server running",server.address());
})