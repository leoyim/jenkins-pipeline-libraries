import com.enterprise.ci.CiConfig
import com.enterprise.ci.RunContext

/**
 * 一键发版编排（编排层）：自动完成 初始化 -> 拉取源码 -> 编译构建 -> 镜像构建
 * -> 部署发布 -> 部署验证 -> 汇总 -> 通知；任一阶段失败即停止后续阶段。
 *
 *   ciPipeline()                                          // 全默认：模拟发版
 *   ciPipeline(app: 'order-api', env: 'staging')          // 指定应用/环境
 *   ciPipeline(simulate: false, deploy: [mode: 'k8s'])    // 真实发版
 *   ciPipeline(stages: [                                  // 自定义阶段（覆盖默认）
 *       '代码扫描': { sh 'sonar-scanner' },
 *       '构建'    : { ciBuild(tool: 'maven') },
 *   ])
 *
 * 需在 node{} 内调用；初始化/汇总/通知由本函数自动完成。
 */
def call(Map cfg = [:]) {
    Map c = CiConfig.validate(CiConfig.resolve([CiConfig.defaults(), cfg]))
    List<Map> stages = resolveStages(c, cfg)
    c.stageTotal = stages.size()

    ciInit(c)
    boolean failed = false
    try {
        for (Map s : stages) {
            if (failed) break
            String name = (String) s.name
            try {
                ciStage(name, (Map) (s.opts ?: [:]), (Closure) s.body)
            } catch (Exception e) {
                failed = true
                throw e
            }
        }
    } finally {
        ciReport()
        try { ciNotify() } catch (Exception e) { ciLog.warn("通知失败（不影响构建结果）：${e.message}") }
    }
}

// ------------------------------------------------------------------
/** 用户自定义 stages 优先；否则按配置生成默认发版阶段 */
private List<Map> resolveStages(Map c, Map cfg) {
    Object custom = cfg.stages != null ? cfg.stages : c.stages
    if (custom instanceof List && !((List) custom).isEmpty()) {
        return (List<Map>) custom
    }
    if (custom instanceof Map && !((Map) custom).isEmpty()) {
        List<Map> out = []
        ((Map) custom).each { k, v ->
            out << [name: k.toString(), body: (v instanceof Map && v.body) ? (Closure) v.body : (Closure) v]
        }
        return out
    }
    // 默认发版阶段
    List<Map> out = []
    out << [name: '拉取源码', body: { defaultCheckout(c) }]
    out << [name: '编译构建', body: { ciBuild() }]
    if (c.docker.enabled != false) {
        out << [name: '镜像构建', body: { ciDockerBuild() }]
    }
    out << [name: '部署发布', body: { ciDeploy() }]
    if (c.verify.enabled != false) {
        out << [name: '部署验证', body: { ciVerify() }]
    }
    return out
}

/** 默认检出：checkout scm，并在需要时用 git commit 刷新版本号 */
private void defaultCheckout(Map c) {
    ciLog.step('拉取源码 (checkout scm)')
    checkout scm
    if (!c.version && env.CI_VERSION == '0.0.0-SNAPSHOT') {
        try {
            String sha = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            if (sha) {
                env.CI_VERSION = sha
                ciLog.info("版本号取自 git commit：${sha}")
            }
        } catch (Exception e) {
            ciLog.warn("无法读取 git commit，版本号保持 ${env.CI_VERSION}：${e.message}")
        }
    }
}
