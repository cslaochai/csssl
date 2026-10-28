import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import './router/guard'
import './style.css'
import { hasPerm } from './lib/perm'

const app = createApp(App)
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.use(ElementPlus, { locale: zhCn })
app.use(router)
// 按钮级权限指令：v-perm="'cert:issue'" 或 v-perm="['app:approve','app:reject']"（任一命中即显示）
app.directive('perm', {
  mounted(el, binding) {
    if (!hasPerm(binding.value)) {
      el.parentNode && el.parentNode.removeChild(el)
    }
  }
})
app.mount('#app')
