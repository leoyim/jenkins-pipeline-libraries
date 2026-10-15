import com.enterprise.ci.CiConfig

/**
 * 构建能力：mock / maven / gradle / npm。
 *
 *   ciBuild()                                        // 默认 mock（配合 SIMULATE 演示）
 *   ciBuild(tool: 'maven', goals: 'clean verify')    // 真实 Maven 构建
 *   ciBuild(build: [tool: 'npm', goals: 'run build']) // 嵌套写法（等价）
 *
 * 模拟判定：tool=='mock' 或全局 simulate=true 时走安全模拟路径（不依赖构建工具）。
 * 真实模式通过 Jenkins 全局工具配置（toolName）解析构建工具 home。
 */
def call(Map cfg = [:]) {
    Map c = CiConfig.resolve([
        CiConfig.defaults(),
        envLayer(),
        lift(cfg, ['tool', 'goals', 'args', 'toolName', 'gradleToolName', 'outputDir', 'archive', 'mockDelay'], 'build'),
    ])
    Map b = (Map) c.build
    String app = c.app?.toString() ?: 'app'
    String version = (env.CI_VERSION ?: (c.version ?: '0.0.0-SNAPSHOT')).toString()
    boolean mock = (b.tool == 'mock') || CiConfig.truthy(c.simulate)

    ciLog.step("构建 ${app} ${version}（模式：${mock ? '模拟' : '真实'}）")
    ciLog.kv([
        '构建工具': b.tool,
        '目标'    : ((b.goals ?: '') + ' ' + (b.args ?: '')).trim(),
        '产物目录': b.outputDir,
    ])

    if (mock) {
        ['编译源码 (compile)', '执行单元测试 (test)', '代码检查 (verify)'].each { String s ->
            ciLog.step("${s} ...")
            sleepMock(b.mockDelay != null ? (b.mockDelay as Integer) : 1)
        }
        String file = "${b.outputDir}/${app}-${version}.jar"
        sh "mkdir -p ${b.outputDir} && printf 'app=${app}\\nversion=${version}\\nbuiltAt=%s\\n' \"\$(date -u +%FT%TZ)\" > ${file} && ls -l ${file}"
        ciLog.ok("模拟构建完成：${file}")
    } else {
        runReal(b, version)
    }

    if (b.archive != false) {
        try {
            archiveArtifacts artifacts: "${b.outputDir}/*.jar", allowEmptyArchive: true
            ciLog.ok('构建产物已归档')
        } catch (Exception e) {
            ciLog.warn("产物归档跳过：${e.message}")
        }
    }
    return [app: app, version: version, artifact: "${b.outputDir}/${app}-${version}.jar"]
}

// ------------------------------------------------------------------
private void runReal(Map b, String version) {
    if (b.tool == 'npm') {
        sh "npm ${b.goals} ${b.args ?: ''}".trim()
        return
    }
    if (b.tool == 'gradle') {
        String home = tool(name: b.gradleToolName ?: 'gradle-8')
        sh "${home}/bin/gradle ${b.goals} ${b.args ?: ''}".trim()
        return
    }
    // maven
    String home = tool(name: b.toolName ?: 'maven-3.9')
    sh "${home}/bin/mvn -B ${b.goals} ${b.args ?: ''} -Drevision=${version}".trim()
}

private void sleepMock(int seconds) {
    if (seconds > 0) sleep(time: seconds, unit: 'SECONDS')
}

/** env 默认值层（未调用 ciInit 时兜底） */
private Map envLayer() {
    Map m = [:]
    if (env.CI_APP) m.app = env.CI_APP
    if (env.CI_TARGET_ENV) m.env = env.CI_TARGET_ENV
    if (env.CI_SIMULATE) m.simulate = env.CI_SIMULATE
    return m
}

/** 支持扁平参数：ciBuild(tool:'maven') 等价于 ciBuild(build:[tool:'maven']) */
private Map lift(Map cfg, List<String> keys, String group) {
    Map m = new LinkedHashMap(cfg ?: [:])
    Map g = [:]
    for (String k : keys) { if (m.containsKey(k)) g[k] = m.remove(k) }
    if (g) m[group] = CiConfig.deepMerge(m[group] instanceof Map ? (Map) m[group] : [:], g)
    return m
}
