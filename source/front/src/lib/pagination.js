/**
 * 全站分页统一口径：列表页的分页查询一律引用这两个常量，
 * 档位与默认条数不允许再写死在页面里。
 */
export const PAGE_SIZES = [10, 20, 50, 100]

export const DEFAULT_PAGE_SIZE = 10

/**
 * 分页 total 归一化。
 *
 * 后端口径：只有 ID 字段（雪花 ID）用 @JsonSerialize(ToStringSerializer) 转字符串，
 * 计数类 long（含 Page.total）一律按数字输出，所以正常拿到的是 number。
 * 这里仍保留一次显式转数，兜住两种意外：个别接口手工拼的 total 是字符串、
 * 或以后有人把 Long→String 的全局规则加回来。el-pagination 的 total 是 Number 类型，
 * 直接透传字符串会触发 "Invalid prop: type check failed for prop total"。
 */
export function toTotal(value) {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
