package com.csssl.config;

/**
 * Jackson 序列化口径说明（本类不再注册任何全局规则，故意留空壳防回归）。
 *
 * <p><b>历史坑</b>：早期这里用
 * {@code builder.serializerByType(Long.class/long, ToStringSerializer.instance)} 把 Long
 * 一刀切成字符串。它除了保护雪花 ID，也顺手把 {@code Page.total}、各类统计计数（证书总数、
 * 账号数、对账条数等）变成了 {@code "0"}、{@code "12"} 这样的字符串，副作用是：</p>
 * <ul>
 *   <li>el-pagination 的 total 是 Number 类型，字符串透传会触发 Vue 告警
 *       "Invalid prop: type check failed for prop total"；</li>
 *   <li>前端每到一处数值运算都要手工 Number() 兜底，且 {@code "0" || 0} 这种兜底不生效。</li>
 * </ul>
 *
 * <p><b>现行口径</b>：只有真正会返回给前端的 ID 字段才转字符串，由字段自己声明
 * {@code @JsonSerialize(using = ToStringSerializer.class)}
 * （雪花 ID 19 位 &gt; JS Number.MAX_SAFE_INTEGER，必须用 string 承载）。计数、序号、
 * 分页 total/size/current 等一律按数字输出。<b>新增 ID 字段时记得补注解，不要再回到全局规则。</b></p>
 */
public final class JacksonConfig {

    private JacksonConfig() {
    }
}
