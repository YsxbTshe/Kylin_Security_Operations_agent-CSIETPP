import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { pinia } from './stores'
import { useAuthStore } from './stores/auth'
import { useThemeStore } from './stores/theme'
import { vLoading } from './ui'
import './styles/main.css'

const app = createApp(App)

app.use(pinia)
app.use(router)
app.directive('loading', vLoading)

const themeStore = useThemeStore(pinia)
themeStore.initialize()

// Resolve auth once at startup so the router guard never has to await it.
const authStore = useAuthStore(pinia)
void authStore.initialize().finally(() => {
  app.mount('#app')
})
