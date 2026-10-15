import com.enterprise.ci.RunContext

/**
 * 阶段包装：醒目头尾 + 可选 timeout/retry + 异常清洗 + 状态同步 + 失败即停。
 *
 *   ciStage('编译构建') { ciBuild() }
 *   ciStage('部署发布', timeoutMinutes: 10, retry: 2) { ciDeploy() }
 *   ciStage('通知', propagate: false) { ... }   // 失败不中断后续阶段
 *
 * 处理顺序：header -> (timeout/retry) -> body -> footer；
 * 任何失败都会写入台账并同步 currentBuild.currentResult。
 */
def call(String name, Map opts = [:], Closure body) {
    int index = nextIndex()
    String total = (env.CI_STAGE_TOTAL ?: '?').toString()
    env.CI_STAGE_CURRENT = name
    long t0 = System.currentTimeMillis()
    ciLog.stageHeader(name, index, total)
    try {
        ciTry(context: name, propagate: false, logStack: opts.logStack != false) {
            execute(opts, body)
        }
    } finally {
        long dur = System.currentTimeMillis() - t0
        String st = (env.CI_LAST_TRY_STATUS ?: 'SUCCESS').toString()
        RunContext.stageEnd(env, name, st, dur)
        ciLog.stageFooter(name, st, dur)
    }
    // 默认失败即停止后续阶段；propagate:false 时继续
    if (env.CI_LAST_TRY_STATUS != 'SUCCESS' && opts.propagate != false) {
        String reason = (env.CI_LAST_TRY_ERROR ?: '未知原因').toString()
        throw new hudson.AbortException("阶段「${name}」失败: ${reason}".toString())
    }
}

// ------------------------------------------------------------------
private int nextIndex() {
    int i = 0
    try { i = ((String) env.CI_STAGE_INDEX ?: '0').toInteger() } catch (ignore) { i = 0 }
    i++
    env.CI_STAGE_INDEX = String.valueOf(i)
    return i
}

/** 执行体：可叠加 timeout / retry */
private void execute(Map opts, Closure body) {
    Integer to = opts.timeoutMinutes != null ? (opts.timeoutMinutes as Integer) : null
    int retries = opts.retry != null ? (opts.retry as Integer) : 0
    int[] attempt = [0]
    Closure work = {
        attempt[0]++
        if (attempt[0] > 1) ciLog.warn("自动重试 第 ${attempt[0]}/${retries} 次")
        body()
    }
    if (to != null && retries > 1) {
        timeout(time: to, unit: 'MINUTES') { retry(retries) { work() } }
    } else if (to != null) {
        timeout(time: to, unit: 'MINUTES') { work() }
    } else if (retries > 1) {
        retry(retries) { work() }
    } else {
        work()
    }
}
