import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './app/router'
import { useAppearanceStore } from './shared/ui/appearance.store'
import './styles/global.css'

const pinia = createPinia()
const app = createApp(App)

app.use(pinia)
useAppearanceStore(pinia).hydrate()
app.use(router).mount('#app')
