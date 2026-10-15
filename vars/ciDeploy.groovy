import com.enterprise.ci.CiConfig

/**
 * 部署能力：mock / ssh / curl / k8s。
 *
 *   ciDeploy()                                          // 默认 mock 滚动发布
 *   ciDeploy(target: 'prod', gate: 'manual')            // 生产发布前人工确认
 *   ciDeploy(mode: 'k8s', namespace: 'prod', image: 'harbor.example.com/app:1.0.0')
 *   ciDeploy(mode: 'ssh', host: '10.0.0.8')             // SSH 滚动替换
 *
 * 约定：镜像优先取入参 image > env.CI_IMAGE（ciDockerBuild 写入） > app:version。
 * 失败会抛出异常，由 ciStage/ciTry 清洗并同步状态。
 */
def call(Map cfg = [:]) {
    Map c = CiConfig.resolve([
        CiConfig.defaults(),
        envLayer(),
        lift(cfg, ['mode', 'target', 'image', 'replicas', 'strategy', 'host', 'namespace', 'url', 'script', 'gate'], 'deploy'),
    ])
    Map d = (Map) c.deploy
    String target = (d.target ?: c.env)?.toString() ?: 'staging'
    String image = (d.image ?: env.CI_IMAGE ?: "${c.app}:${env.CI_VERSION ?: '0.0.0-SNAPSHOT'}").toString()
    boolean mock = (d.mode == 'mock') || CiConfig.truthy(c.simulate)

    ciLog.step("部署 ${c.app} -> ${target}")
    ciLog.kv([
        '部署模式': mock ? '模拟' : d.mode,
        '镜像'    : image,
        '副本数'  : d.replicas,
        '策略'    : d.strategy,
    ])

    // 人工确认闸门（用于生产等重要环境）
    if (d.gate == 'manual') {
        ciLog.warn("部署闸门：等待人工确认 (gate=manual)")
        input message: "确认部署 ${c.app} ${image} 到 ${target} ?", ok: '确认部署'
    }

    if (mock) {
        mockDeploy(d, image)
    } else if (d.mode == 'ssh') {
        sh """ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 ${d.host} '
            docker pull ${image}
            docker stop ${c.app} || true && docker rm ${c.app} || true
            docker run -d --name ${c.app} --restart=unless-stopped -p 8080:8080 ${image}'
        """.stripIndent().trim()
    } else if (d.mode == 'curl') {
        String endpoint = d.url ?: d.script
        if (!endpoint) throw new hudson.AbortException('deploy.mode=curl 需要提供 url（部署接口地址）')
        String json = groovy.json.JsonOutput.toJson([image: image, replicas: d.replicas, app: c.app, env: target])
        sh "curl -fsS -X POST -H 'Content-Type: application/json' -H \"Authorization: Bearer \${DEPLOY_TOKEN:-}\" -d '${json}' '${endpoint}'"
    } else if (d.mode == 'k8s') {
        sh "kubectl -n ${d.namespace} set image deployment/${c.app} ${c.app}=${image}"
        sh "kubectl -n ${d.namespace} rollout status deployment/${c.app} --timeout=180s"
    } else {
        throw new hudson.AbortException("不支持的部署模式: ${d.mode}")
    }

    env.CI_DEPLOY_TARGET = target
    env.CI_DEPLOY_STATUS = 'DONE'
    ciLog.ok("部署完成：${c.app} -> ${target}（image=${image}）")
    return [target: target, image: image]
}

// ------------------------------------------------------------------
/** 模拟发布：按滚动策略逐步输出（用于演示/联调，不影响真实环境） */
private void mockDeploy(Map d, String image) {
    int replicas = (d.replicas ?: 2) as Integer
    List<String> steps = [
        "发布单校验：${image} (strategy=${d.strategy}, replicas=${replicas})",
        "拉取镜像（模拟）：节点 1/${replicas}",
        "滚动替换：启动新实例并通过健康检查后摘除旧实例",
        "副本数收敛：${replicas}/${replicas} 就绪",
    ]
    steps.eachWithIndex { String s, int i ->
        ciLog.step("[${i + 1}/${steps.size()}] ${s}")
        sleepMock(1)
    }
}

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
