
const express = require('express')

const app = express()
const server = app.listen(process.env.HTTP_PORT)

app.use((req, res, next) => {
    const found = req.url.match("^(.*)-prod\.js$")
    if(found) req.url = found[1]+".js"
    next();
});

app.use(express.static('src'))
app.use(express.static('vendor'))