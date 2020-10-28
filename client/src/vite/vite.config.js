/*
import reactPlugin from 'vite-plugin-react'
export default {
  jsx: 'react',
  plugins: [reactPlugin]
}*/
export default {
    jsx: 'react',
    alias: {
        'react': "@pika/react/source.development.js", //'@pika/react',
        'react-dom': "@pika/react-dom/source.development.js", //'@pika/react-dom',
    },
}