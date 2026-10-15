import com.enterprise.ci.CiConfig
import com.enterprise.ci.RunContext

/**
 * 流水线初始化：解析配置、重置状态台账、同步构建展示信息、打印运行摘要。
 * 必须在 node{} 内、所有 ciStage 之前调用（ciPipeline 会自动调用）。
 *
 *   ciInit(app: 'order-api', env: 'staging', simulate: true)
 *
 * @return 合并后的完整配置（供编排层复用）
 */
def call(Map cfg = [:]) {
    Map config = CiConfig.validate(CiConfig.deepMerge(CiConfig.defaults(), cfg))

    // 1) 状态台账重置（env 为唯一事实来源）
    RunContext.reset(env)
    env.CI_START_TIME   = String.valueOf(System.currentTimeMillis())
    env.CI_APP          = String.valueOf(config.app)
    env.CI_TARGET_ENV   = String.valueOf(config.env)
    env.CI_SIMULATE     = String.valueOf(config.simulate)
    env.CI_VERSION      = resolveVersion(config.version)
    env.CI_LOG_COLOR    = String.valueOf(config.color ?: 'auto')
    if (config.stageTotal != null) env.CI_STAGE_TOTAL = String.valueOf(config.stageTotal)

    // 2) Jenkins 展示信息同步
    currentBuild.currentResult = 'SUCCESS'
    currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.CI_APP} ${env.CI_VERSION}"
    currentBuild.description = "env=${env.CI_TARGET_ENV} | RUNNING"

    // 3) 运行摘要（醒目）
    ciLog.banner("发版流水线 ${env.CI_APP} ${env.CI_VERSION}")
    ciLog.kv([
        '应用'    : env.CI_APP,
        '版本'    : env.CI_VERSION,
        '目标环境': env.CI_TARGET_ENV,
        '分支'    : branchName(),
        '构建号'  : "#${env.BUILD_NUMBER}",
        '执行节点': env.NODE_NAME ?: '-',
        '模拟模式': CiConfig.truthy(env.CI_SIMULATE) ? '是（全部步骤安全模拟，不影响真实环境）' : '否（真实执行）',
        '开始时间': new Date().format('yyyy-MM-dd HH:mm:ss'),
    ])
    return config
}

// ------------------------------------------------------------------
/** 版本号解析优先级：显式参数 > params.VERSION > git short sha > 0.0.0-SNAPSHOT */
private String resolveVersion(String explicit) {
    if (explicit) return explicit.toString()
    try { if (params?.VERSION) return params.VERSION.toString() } catch (ignore) {}
    try {
        String sha = sh(script: 'git rev-parse --short HEAD 2>/dev/null || true', returnStdout: true).trim()
        if (sha) return sha
    } catch (ignore) {}
    return '0.0.0-SNAPSHOT'
}

private String branchName() {
    String b = env.GIT_BRANCH ?: env.BRANCH_NAME
    if (b) return b
    try { if (params?.BRANCH) return params.BRANCH.toString() } catch (ignore) {}
    return '-'
}
