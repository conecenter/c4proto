
const HtmlWebpackPlugin = require('html-webpack-plugin')
const config = require("./webpack.include.js").config(HtmlWebpackPlugin,"test")
module.exports = env=>[config("react-app"), config("sse")]
