import com.enterprise.ci.Ansi
import com.enterprise.ci.CiConfig
import com.enterprise.ci.RunContext

/**
 * 统一日志门面：醒目格式 + 多行缩进 + 可选 ANSI 颜色。
 *
 * 用法：
 *   ciLog('任意文本')            // 等价 info
 *   ciLog.info / warn / error / ok / debug / step
 *   ciLog.banner('标题')         // 64 列醒目横幅
 *   ciLog.kv([k: v, ...])        // 对齐键值表（兼容中文宽度）
 *   ciLog.raw('原始文本')         // 不加工直接输出
 *
 * 颜色开关：env.CI_LOG_COLOR = true|false|auto(默认=关)。
 * 未安装 AnsiColor 插件时保持 false，日志依然通过符号/对齐/分隔线保持醒目。
 */

def call(Object... args) { info(args) }

def info(Object... args)  { emit('INFO', join(args)) }
def warn(Object... args)  { emit('WARN', join(args)) }
def error(Object... args) { emit('ERROR', join(args)) }
def ok(Object... args)    { emit('OK', join(args)) }
def step(Object... args)  { emit('STEP', join(args)) }
def debug(Object... args) { if (CiConfig.truthy(env.CI_DEBUG)) emit('DEBUG', join(args)) }

/** 不加工直接输出一行 */
def raw(String s) { println s }

/** 醒目横幅：用于流水线级标题 */
def banner(String title, String subtitle = '') {
    boolean c = colorOn()
    String bar = Ansi.line(64, '\u2550') // ═
    println ''
    println Ansi.colorize(bar, Ansi.CYAN, c)
    println Ansi.colorize('\u2605 ' + title, Ansi.BOLD + Ansi.CYAN, c)
    if (subtitle) println Ansi.colorize('  ' + subtitle, Ansi.GREY, c)
    println Ansi.colorize(bar, Ansi.CYAN, c)
}

/** 阶段头：──▶ 阶段 [2/6] 编译构建 ── */
def stageHeader(String name, int index, String total) {
    boolean c = colorOn()
    String bar = Ansi.line(64, '\u2500') // ─
    String idx = (total && total != '0' && total != '?') ? "[${index}/${total}] " : (index > 0 ? "[${index}] " : '')
    println ''
    println Ansi.colorize(bar, Ansi.BLUE, c)
    println Ansi.colorize('\u25B6 ' + idx + name, Ansi.BOLD, c) // ▶
    println Ansi.colorize(bar, Ansi.BLUE, c)
}

/** 阶段尾：状态 + 耗时 */
def stageFooter(String name, String status, long durationMs) {
    boolean c = colorOn()
    String st = Ansi.statusLabel(status, c)
    String dur = RunContext.humanDuration(durationMs)
    println Ansi.colorize(Ansi.line(64, '\u2500'), Ansi.GREY, c)
    println "${Ansi.statusSymbol(status)} 阶段「${name}」结束 · 状态 ${st} · 耗时 ${dur}"
}

/** 对齐键值表（中文按 2 列宽计算） */
def kv(Map m, String indent = '  ') {
    boolean c = colorOn()
    int w = 0
    m.each { k, v -> w = Math.max(w, Ansi.displayWidth(k == null ? '' : k.toString())) }
    m.each { k, v ->
        String key = Ansi.padRight(k == null ? '' : k.toString(), w)
        println "${indent}${Ansi.colorize(key, Ansi.GREY, c)} : ${v}"
    }
}

/** 纯分隔线 */
def rule(String ch = '\u2500') {
    println Ansi.colorize(Ansi.line(64, ch), Ansi.GREY, colorOn())
}

// ------------------------------------------------------------------
private void emit(String level, String msg) {
    boolean c = colorOn()
    String tag = Ansi.tag(level, c)
    String sym = Ansi.symbol(level, c)
    List<String> lines = (msg == null || msg == '') ? [''] : msg.toString().readLines()
    lines.eachWithIndex { String l, int i ->
        if (i == 0) println "${tag} ${sym} ${l}"
        else println "        ${l}" // 续行对齐
    }
}

private String join(Object... args) {
    List<String> parts = []
    for (Object a : args) {
        if (a == null) continue
        if (a instanceof Collection) a.each { parts << (it == null ? 'null' : it.toString()) }
        else parts << a.toString()
    }
    return parts.join(' ')
}

private boolean colorOn() {
    String flag = null
    try { flag = env.CI_LOG_COLOR?.toString() } catch (ignore) {}
    if (flag == null || flag == 'auto') return false
    return ['true', '1', 'yes', 'on'].contains(flag.toLowerCase())
}
