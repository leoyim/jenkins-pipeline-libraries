import com.enterprise.ci.CiConfig
import com.enterprise.ci.RunContext

/**
 * 结果汇总：状态同步 + 阶段台账表 + 错误摘要。
 * 用法（二选一）：
 *   post { always { ciReport() } }   // 声明式流水线
 *   finally { ciReport() }           // 脚本化流水线（ciPipeline 内部已自动调用）
 *
 * 职责：
 *  1) 末次状态同步：台账状态与 Jenkins 结果取更严重者，绝不把失败改写成成功；
 *  2) 输出阶段耗时表、错误摘要、构建链接；
 *  3) 刷新 displayName/description，让任务列表一眼看到结果。
 */
def call(Map opts = [:]) {
    // 1) 状态同步
    String ledger = com.enterprise.ci.RunContext.status(env)
    String jenkins = 'SUCCESS'
    try { jenkins = currentBuild.currentResult ?: 'SUCCESS' } catch (ignore) {}
    String finalSt = com.enterprise.ci.RunContext.merge(jenkins, ledger)
    try { currentBuild.currentResult = finalSt } catch (ignore) {}

    long durMs = 0
    try { durMs = System.currentTimeMillis() - ((env.CI_START_TIME ?: '0') as Long) } catch (ignore) {}
    String dur = com.enterprise.ci.RunContext.humanDuration(durMs)

    List<Map> stages = com.enterprise.ci.RunContext.stages(env)
    List<String> errs = com.enterprise.ci.RunContext.errors(env)

    // 2) 汇总输出
    ciLog.banner("构建汇总 ${env.CI_APP ?: ''} ${env.CI_VERSION ?: ''}".trim())
    if (stages) {
        int w = 0
        stages.each { w = Math.max(w, com.enterprise.ci.Ansi.displayWidth((String) it.name)) }
        stages.each { Map s ->
            String sym = com.enterprise.ci.Ansi.statusSymbol((String) s.status)
            String name = com.enterprise.ci.Ansi.padRight((String) s.name, w)
            String d = com.enterprise.ci.Ansi.padLeft(com.enterprise.ci.RunContext.humanDuration(s.durationMs as long), 8)
            ciLog.raw("  ${sym} ${name}  ${s.status.padRight(8)} ${d}")
        }
        ciLog.raw('')
    }
    ciLog.kv([
        '最终结果': finalSt,
        '总耗时'  : dur,
        '失败阶段': String.valueOf(stages.count { it.status != 'SUCCESS' }),
        '错误摘要': errs ? errs.join(' ; ') : '-',
        '构建链接': env.BUILD_URL ?: '-',
    ])

    // 3) 任务列表展示信息
    try {
        currentBuild.description = "${finalSt} | ${env.CI_APP ?: ''} ${env.CI_VERSION ?: ''} | ${dur}".toString()
    } catch (ignore) {}
    if (finalSt == 'SUCCESS') {
        ciLog.ok('流水线执行成功')
    } else {
        ciLog.error("流水线执行失败（${finalSt}），请依据上方错误摘要定位")
    }
    return finalSt
}
