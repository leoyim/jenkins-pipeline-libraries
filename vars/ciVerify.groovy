import com.enterprise.ci.CiConfig

/**
 * 部署后验证：轮询健康检查，未就绪则重试，最终失败抛异常让状态正确翻转。
 *
 *   ciVerify()                                   // 默认 http://localhost:8080/actuator/health
 *   ciVerify(url: 'https://app.example.com/health', attempts: 10, interval: 5)
 */
def call(Map cfg = [:]) {
    Map c = CiConfig.resolve([
        CiConfig.defaults(),
        envLayer(),
        lift(cfg, ['enabled', 'url', 'attempts', 'interval', 'expect', 'mock'], 'verify'),
    ])
    Map v = (Map) c.verify
    if (v.enabled == false) {
        ciLog.info('部署后验证已关闭 (enabled=false)，跳过')
        return null
    }
    boolean mock = (v.mock != null) ? CiConfig.truthy(v.mock) : CiConfig.truthy(c.simulate)
    int attempts = (v.attempts ?: 5) as Integer
    int interval = (v.interval ?: 2) as Integer

    ciLog.step("部署后健康检查：${v.url}（最多 ${attempts} 次，间隔 ${interval}s）")
    for (int i = 1; i <= attempts; i++) {
        if (mock) {
            ciLog.debug("第 ${i}/${attempts} 次探测（模拟）：HTTP 200 OK")
            sleepMock(1)
            ciLog.ok("健康检查通过（第 ${i}/${attempts} 次，模拟 HTTP 200）")
            return [healthy: true, attempts: i, mock: true]
        }
        String code = sh(
            script: "curl -sS -o /dev/null -w '%{http_code}' --max-time 5 '${v.url}' || echo 000",
            returnStdout: true).trim()
        if (code == String.valueOf(v.expect)) {
            ciLog.ok("健康检查通过（第 ${i}/${attempts} 次，HTTP ${code}）")
            return [healthy: true, attempts: i, mock: false]
        }
        ciLog.warn("第 ${i}/${attempts} 次探测未就绪（HTTP ${code == '000' ? '不可达/超时' : code}）")
        if (i < attempts) sleepMock(interval)
    }
    throw new hudson.AbortException(
        "部署后健康检查失败：${v.url} 连续 ${attempts} 次未返回 HTTP ${v.expect}".toString())
}

// ------------------------------------------------------------------
private void sleepMock(int seconds) {
    if (seconds > 0) sleep(time: seconds, unit: 'SECONDS')
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
