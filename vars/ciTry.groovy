import com.enterprise.ci.ErrorFormatter
import com.enterprise.ci.RunContext

/**
 * 错误捕获原语：异常清洗 + 状态同步 + 可选向上抛。
 *
 *   ciTry { ... }                       // 默认：失败清洗日志后向上抛
 *   ciTry(propagate: false) { ... }     // 失败仅记录，流水线继续（阶段记为失败）
 *   ciTry(context: '镜像构建') { ... }   // 指定错误上下文名
 *   ciTry(logStack: false) { ... }      // 不打印堆栈摘要
 *
 * 约定：
 *  - FlowInterruptedException（手动中止/超时）始终向上抛，保留 ABORTED 语义；
 *  - 其余异常清洗后以 hudson.AbortException 抛出，Jenkins 只打印一行 ERROR，避免堆栈刷屏。
 */
def call(Map opts = [:], Closure body) {
    try {
        def r = body()
        env.CI_LAST_TRY_STATUS = 'SUCCESS'
        return r
    } catch (Exception e) {
        handle(e, opts)
    }
}

// ------------------------------------------------------------------
private void handle(Exception e, Map opts) {
    boolean propagate = opts.propagate != false
    boolean logStack = opts.logStack != false
    String ctx = (opts.context ?: env.CI_STAGE_CURRENT ?: '步骤').toString()
    Map info = ErrorFormatter.analyze(e)
    boolean aborted = (info.category == 'ABORT')
    String status = aborted ? 'ABORTED' : 'FAILURE'

    // 1) 状态同步：台账 + Jenkins 展示 + 供 ciStage 读取的最近一次结果
    RunContext.mark(env, status)
    RunContext.addError(env, "[${info.category}] ${info.reason}".toString())
    currentBuild.currentResult = status
    env.CI_LAST_TRY_STATUS = status
    env.CI_LAST_TRY_ERROR = info.reason

    // 2) 清洗后的结构化错误输出（醒目、可检索）
    ciLog.error("上下文「${ctx}」执行失败 · 分类=${info.category}")
    ciLog.error("原因：${info.reason}")
    ciLog.error("建议：${info.hint}")
    if (info.exitCode) ciLog.error("退出码：${info.exitCode}")
    if (logStack) {
        List<String> frames = ErrorFormatter.stackFrames(e, 5)
        if (frames) {
            ciLog.debug('堆栈摘要（已过滤 Jenkins/Groovy/JDK 内部帧）：')
            frames.each { ciLog.raw('        ' + it) }
        }
        ErrorFormatter.causeChain(e, 2).each { ciLog.raw('        ' + it) }
    }

    // 3) 向上抛策略
    if (aborted) throw e // 保留中止语义，Jenkins 记 ABORTED
    if (propagate) {
        throw new hudson.AbortException("「${ctx}」失败: ${info.reason}".toString())
    }
}
