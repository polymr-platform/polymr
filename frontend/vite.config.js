import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
// https://vite.dev/config/
export default defineConfig({
	server: {
		host: '0.0.0.0',
		port: 5174,
		strictPort: true,
		proxy: { '/api': { target: 'http://localhost:5050', changeOrigin: true } }
	},
	plugins: [
		vue({ template: { compilerOptions: { isCustomElement: (tag) => tag.startsWith('mcp-') } } }),
	]
})
