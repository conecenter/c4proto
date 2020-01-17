
var config = require("./webpack.include.js").config
module.exports = env=>[
    config("test","react-app"),
    config("test","sse")
]
