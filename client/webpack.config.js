const AutoDllPlugin = require('autodll-webpack-plugin')
const config = require("./webpack.include.js").config("test",__dirname,[])
module.exports = env=>{
    const conf = config(["react-app","sse","main"],env)
    return {
        ...conf,
        plugins: [
            ...conf.plugins,
            /*
            new AutoDllPlugin({
                filename: '[name].js',
                entry: {
                  vendor: [
                    'react',
                    'react-dom',
                    "immutability-helper",
                    "react-sortable-hoc",
                    "@material-ui/core",
                    "@material-ui/icons",
                  ]
                }
            }),*/
        ],
        optimization: {
            minimize: false,
        }
    }
}
