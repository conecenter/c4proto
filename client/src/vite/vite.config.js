/*
import reactPlugin from 'vite-plugin-react'
export default {
  jsx: 'react',
  plugins: [reactPlugin]
}
    proxy: {
        '/sse': 'https://'+host+'/sse',
        '/connect': 'https://'+host+'/connect',
    },
*/

const host = process.env.C4HOST
console.log("host ("+host+")")
export default {
    jsx: 'react',
    alias: {
        'react': "@pika/react/source.development.js", //'@pika/react',
        'react-dom': "@pika/react-dom/source.development.js", //'@pika/react-dom',
    },
    hmr: false,
}