import { createApp } from 'vue';
import './style.css'
import 'prismjs/themes/prism-tomorrow.css'
import App from './App.vue';
import router from './router';
import { setLocationTrackingEnabled } from './state/locationTracking';
const loadMcpView = () => import('./vendor/mcp-view.esm.js')
// import('https://cdn.jsdelivr.net/gh/mcp4h/mcp-ui@master/dist/mcp-view.esm.js')
// import('/home/alex/celerex/workspaces/mcp4h/mcp-ui/dist/mcp-view.esm.js')
const loadPrism = async() => {
	const prism = await import('prismjs')
	const Prism = prism.default || prism
	if (typeof window !== 'undefined') {
		window.Prism = Prism
	}
	await Promise.all(
		[
			import('prismjs/components/prism-python'),
			import('prismjs/components/prism-java'),
			import('prismjs/components/prism-javascript'),
			import('prismjs/components/prism-typescript'),
			import('prismjs/components/prism-json'),
			import('prismjs/components/prism-bash'),
			import('prismjs/components/prism-sql'),
			import('prismjs/components/prism-rust'),
			import('prismjs/components/prism-markdown'),
			import('prismjs/components/prism-markup'),
			import('prismjs/components/prism-groovy'),
		]
	)
}
const startApp = () => {
	const app = createApp(App)
	app.config.compilerOptions.isCustomElement = (tag) => tag.startsWith('mcp-')
	app.use(router).mount('#app')
	const locationPreference = localStorage.getItem('polymr.location.exact') === 'true'
	setLocationTrackingEnabled(locationPreference)
	if ('serviceWorker' in navigator) {
		navigator.serviceWorker.register('/sw.js').catch(() => {})
	}
}
Promise.all([loadPrism(), loadMcpView()]).finally(startApp)
