import com.enterprise.ci.CiConfig
import com.enterprise.ci.RunContext

/**
 * 结果通知：mock（打印通知卡片）/ webhook（企业微信、钉钉等）/ email。
 *
 *   ciNotify()                                    // 默认 mock 卡片
 *   ciNotify(channel: 'webhook', webhook: 'https://qyapi.weixin.qq.com/...')
 *   ciNotify(on: 'failure')                       // 仅失败时通知
 *
 * 通知失败绝不影响构建结果（内部消化为 WARN）。
 */
def call(Map cfg = [:]) {
    Map c = CiConfig.resolve([
        CiConfig.defaults(),
        envLayer(),
        lift(cfg, ['enabled', 'channel', 'webhook', 'on', 'to'], 'notify'),
    ])
    Map n = (Map) c.notify
    if (n.enabled == false) {
        ciLog.debug('通知已关闭 (enabled=false)')
        return null
    }
    String status = RunContext.status(env)
    if (n.on == 'failure' && status == 'SUCCESS') {
        ciLog.debug('通知策略 on=failure，本次成功不发送')
        return null
    }
    Map data = collect(status)
    try {
        send(n, status, data)
    } catch (Exception e) {
        ciLog.warn("通知发送失败（不影响构建结果）：${e.message}")
    }
    return data
}

// ------------------------------------------------------------------
/** 汇总通知内容 */
private Map collect(String status) {
    List<Map> stages = RunContext.stages(env)
    String stageLine = stages
        .collect { "${it.name}:${it.status}(${RunContext.humanDuration(it.durationMs as long)})".toString() }
        .join(' | ')
    List<String> errs = RunContext.errors(env)
    long durMs = 0
    try { durMs = System.currentTimeMillis() - ((env.CI_START_TIME ?: '0') as Long) } catch (ignore) {}
    return [
        '应用'  : env.CI_APP ?: '-',
        '版本'  : env.CI_VERSION ?: '-',
        '环境'  : env.CI_TARGET_ENV ?: '-',
        '结果'  : status,
        '耗时'  : RunContext.humanDuration(durMs),
        '构建'  : "${env.JOB_NAME ?: '-'} #${env.BUILD_NUMBER ?: '-'}",
        '链接'  : env.BUILD_URL ?: '-',
        '阶段'  : stageLine ?: '-',
        '错误'  : errs ? errs.join(' ; ') : '-',
    ]
}

private void send(Map n, String status, Map data) {
    String symbol = com.enterprise.ci.Ansi.statusSymbol(status)
    if (n.channel == 'webhook') {
        if (!n.webhook) throw new IllegalArgumentException('notify.channel=webhook 需要提供 webhook 地址')
        StringBuilder text = new StringBuilder()
        text.append("${symbol} 发版通知 ${data['应用']} ${data['版本']}\n".toString())
        data.each { k, v -> text.append("${k}: ${v}\n".toString()) }
        String json = groovy.json.JsonOutput.toJson([msgtype: 'text', text: [content: text.toString()]])
        String body = json.replace("'", "'\\''")
        sh "curl -fsS -X POST -H 'Content-Type: application/json' -d '${body}' '${n.webhook}'"
        ciLog.ok("通知已发送（webhook）：${n.webhook}")
        return
    }
    if (n.channel == 'email') {
        mail to: (n.to ?: 'team@example.com').toString(),
             subject: "[${data['结果']}] ${data['应用']} ${data['版本']} 发版 ${data['构建']}".toString(),
             body: data.collect { k, v -> "${k}: ${v}".toString() }.join('\n')
        ciLog.ok("通知已发送（email）：${n.to ?: 'team@example.com'}")
        return
    }
    // mock：控制台通知卡片
    ciLog.banner("发版通知 ${data['应用']} ${data['版本']} ${symbol} ${data['结果']}")
    ciLog.kv(data)
}

private Map envLayer() {
    Map m = [:]
    if (env.CI_APP) m.app = env.CI_APP
    if (env.CI_TARGET_ENV) m.env = env.CI_TARGET_ENV
    if (env.CI_SIMULATE) m.simulate = env.CI_SIMULATE
    return m
}

private Map lift(Map cfg, List<String> keys, String group) {
    Map m = new LinkedHashMap(cfg ?: [:])
    Map g = [:]
    for (String k : keys) { if (m.containsKey(k)) g[k] = m.remove(k) }
    if (g) m[group] = CiConfig.deepMerge(m[group] instanceof Map ? (Map) m[group] : [:], g)
    return m
}
