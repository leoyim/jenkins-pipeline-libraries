import com.enterprise.ci.CiConfig

/**
 * 镜像构建能力：mock / real（docker build [+ push]）。
 *
 *   ciDockerBuild()                                            // 默认 mock
 *   ciDockerBuild(registry: 'harbor.example.com', push: true)  // 真实构建并推送
 *
 * 成功后把镜像坐标写入 env.CI_IMAGE，供 ciDeploy 默认引用。
 */
def call(Map cfg = [:]) {
    Map c = CiConfig.resolve([
        CiConfig.defaults(),
        envLayer(),
        lift(cfg, ['enabled', 'mode', 'registry', 'namespace', 'dockerfile', 'context', 'push'], 'docker'),
    ])
    Map d = (Map) c.docker
    if (d.enabled == false) {
        ciLog.info('镜像构建已关闭 (enabled=false)，跳过')
        return null
    }
    String version = (env.CI_VERSION ?: '0.0.0-SNAPSHOT').toString()
    String ns = (d.namespace ?: c.app)?.toString() ?: 'app'
    String image = "${d.registry}/${ns}/${c.app}:${version}".toString()
    boolean mock = (d.mode == 'mock') || CiConfig.truthy(c.simulate)

    ciLog.step("构建容器镜像（模式：${mock ? '模拟' : '真实'}）")
    ciLog.kv(['镜像': image, 'Dockerfile': d.dockerfile, '上下文': d.context])

    if (mock) {
        sleepMock(1)
        ciLog.ok("模拟镜像构建完成：${image}（layers=7, size=128MB, high=0）")
    } else {
        sh "docker build -f ${d.dockerfile} -t ${image} ${d.context}"
        boolean doPush = d.push != null ? CiConfig.truthy(d.push) : true
        if (doPush) {
            sh "docker push ${image}"
            ciLog.ok("镜像已推送：${image}")
        }
    }
    env.CI_IMAGE = image
    return image
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
