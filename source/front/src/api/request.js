import axios from 'axios'
import { ElMessage } from 'element-plus'

// CSSSL 后端 context-path 为 /ssl（vite 代理 /ssl → http://localhost:8092）
const request = axios.create({
  baseURL: '/ssl',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

let redirectingToLogin = false

let lastErrorMessage = ''
let lastErrorAt = 0
const ERROR_DEDUP_MS = 2000

function notifyError(message) {
  const text = message || '操作失败，请稍后重试'
  const now = Date.now()
  if (text === lastErrorMessage && now - lastErrorAt < ERROR_DEDUP_MS) {
    return
  }
  lastErrorMessage = text
  lastErrorAt = now
  ElMessage.error(text)
}

function httpStatusFallback(status) {
  const map = {
    400: '请求参数不正确',
    403: '没有权限访问',
    404: '请求的资源不存在',
    405: '请求方法不支持',
    415: '请求媒体类型不支持',
    500: '系统繁忙，请稍后重试',
    502: '服务暂时不可用，请稍后重试',
    503: '服务暂时不可用，请稍后重试',
    504: '服务暂时不可用，请稍后重试'
  }
  return map[status] || `请求失败（${status}）`
}

request.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  error => Promise.reject(error)
)

request.interceptors.response.use(
  response => {
    if (response.config.responseType === 'blob') {
      return response.data
    }
    const res = response.data
    // 证书/CRL/私钥下载接口返回裸 PEM 文本（text/plain），直接放行
    if (typeof res === 'string') {
      return res
    }
    if (res.code !== 200) {
      const message = res.message || '操作失败'
      const isLoginRequest = (response.config?.url || '').includes('/auth/login')
      if (!response.config?.silent && !isLoginRequest) {
        notifyError(message)
      }
      const err = new Error(message)
      err.code = res.code
      return Promise.reject(err)
    }
    return res
  },
  error => {
    const config = error.config || {}
    const silent = !!config.silent
    const isLoginRequest = (config.url || '').includes('/auth/login')

    if (error.code === 'ERR_CANCELED' || error.name === 'CanceledError' || axios.isCancel?.(error)) {
      return Promise.reject(error)
    }

    const { response } = error
    if (response) {
      const { status, data } = response
      const message = data?.message
      if (status === 401) {
        if (!isLoginRequest) {
          localStorage.removeItem('token')
          localStorage.removeItem('username')
          localStorage.removeItem('role')
          if (!redirectingToLogin) {
            redirectingToLogin = true
            window.location.href = '/login'
          }
        }
        return Promise.reject(error)
      }
      // 真实 HTTP 403：已登录但角色不足，只提示权限不足，不跳登录页
      if (status === 403) {
        if (!silent) {
          notifyError('权限不足，无法执行该操作')
        }
        return Promise.reject(error)
      }
      if (!silent) {
        notifyError(message || httpStatusFallback(status))
      }
      return Promise.reject(error)
    }

    if (!silent) {
      notifyError(error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '网络异常，请检查网络连接')
    }
    return Promise.reject(error)
  }
)

export default request
