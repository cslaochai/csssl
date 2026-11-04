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

// Element Plus 2.x 已知无害告警：el-sub-menu 内部渲染展开箭头 ElIcon 及其
// 子菜单 slot 时，会以"非 render 函数内"的方式调用 slot，触发 Vue dev 警告
// "Slot "default" invoked outside of the render function"。该告警不影响功能、
// 也不会进入生产构建。这里仅针对 el-sub-menu 变体静默，不屏蔽其它真实告警。
const isEpSubMenuSlotWarn = (msg, trace) =>
  typeof msg === 'string' &&
  msg.includes('invoked outside of the render function') &&
  typeof trace === 'string' &&
  trace.includes('el-sub-menu')
app.config.warnHandler = (msg, instance, trace) => {
  if (isEpSubMenuSlotWarn(msg, trace)) return
  console.warn(`[Vue warn]: ${msg}${trace ? '\n' + trace : ''}`)
}
// 把 Unhandled error during execution of native event handler 的真实堆栈打出来
app.config.errorHandler = (err, instance, info) => {
  console.error('[Vue ERROR]', err?.stack || err, '\ninfo:', info)
}

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.use(ElementPlus, { locale: zhCn })
app.use(router)
// 按钮级权限指令：v-perm="'cert:revoke'" 或 v-perm="['app:approve','app:reject']"（任一命中即显示）
app.directive('perm', {
  mounted(el, binding) {
    if (!hasPerm(binding.value)) {
      el.parentNode && el.parentNode.removeChild(el)
    }
  }
})
app.mount('#app')
