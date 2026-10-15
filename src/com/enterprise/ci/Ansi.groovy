package com.enterprise.ci

/**
 * 日志装饰引擎：ANSI 颜色码 + Unicode 符号 + 框线排版。
 *
 * 纯字符串逻辑：不调用任何 Jenkins pipeline step，可脱离 Jenkins 独立单测。
 * 即使不启用 ANSI 颜色（未安装 AnsiColor 插件），也通过 Unicode 符号、
 * 固定宽度对齐、分隔线保证日志醒目。
 */
class Ansi implements Serializable {

    private static final long serialVersionUID = 1L

    // ---------------- ANSI 颜色 ----------------
    static final String RESET  = '\u001B[0m'
    static final String BOLD   = '\u001B[1m'
    static final String GREY   = '\u001B[90m'
    static final String RED    = '\u001B[31m'
    static final String GREEN  = '\u001B[32m'
    static final String YELLOW = '\u001B[33m'
    static final String BLUE   = '\u001B[34m'
    static final String PURPLE = '\u001B[35m'
    static final String CYAN   = '\u001B[36m'

    /** 日志级别 -> [颜色, 符号] */
    static final Map<String, List> LEVELS = [
        INFO : [CYAN,  '\u2139'],   // info 符号
        WARN : [YELLOW, '!'],
        ERROR: [RED,   '\u2716'],   // ✖
        OK   : [GREEN, '\u2714'],   // ✔
        DEBUG: [GREY,  '\u00B7'],   // ·
        STEP : [BLUE,  '\u25B8'],   // ▸
    ].asImmutable()

    static final Map<String, String> STATUS_COLOR = [
        SUCCESS  : GREEN,
        UNSTABLE : YELLOW,
        FAILURE  : RED,
        ABORTED  : PURPLE,
        NOT_BUILT: GREY,
    ].asImmutable()

    static String colorize(String text, String code, boolean enabled) {
        if (!enabled || text == null || code == null) return text
        return code + text + RESET
    }

    /** 构建状态 -> 符号 */
    static String statusSymbol(String status) {
        switch (status?.toUpperCase()) {
            case 'SUCCESS':   return '\u2714'      // ✔
            case 'UNSTABLE':  return '!'
            case 'FAILURE':   return '\u2716'      // ✖
            case 'ABORTED':   return '\u2298'      // ⊘
            default:          return '\u00B7'      // ·
        }
    }

    /** 日志级别标签，固定显示宽度 7：[INFO ] [ERROR] [ OK ] */
    static String tag(String level, boolean color) {
        String name = (level ?: 'INFO').toUpperCase()
        String body = ('[' + name + ']').padRight(7)
        List meta = LEVELS[name]
        if (meta == null) return colorize(body, CYAN, color)
        return colorize(body, (String) meta[0], color)
    }

    /** 级别符号（颜色跟随级别） */
    static String symbol(String level, boolean color) {
        String name = (level ?: 'INFO').toUpperCase()
        List meta = LEVELS[name]
        String s = meta != null ? (String) meta[1] : '\u00B7'
        return colorize(s, meta != null ? (String) meta[0] : GREY, color)
    }

    /**
     * 显示宽度（中日韩全角字符按 2 列计算），
     * 保证 kv 表格/边框在中文环境下对齐。
     */
    static int displayWidth(String s) {
        if (s == null) return 0
        int w = 0
        for (int i = 0; i < s.length(); i++) {
            int c = s.codePointAt(i)
            if (c >= 0x1100 && (
                    c <= 0x115F ||
                    c == 0x2329 || c == 0x232A ||
                    (c >= 0x2E80 && c <= 0xA4CF && c != 0x303F) ||
                    (c >= 0xAC00 && c <= 0xD7A3) ||
                    (c >= 0xF900 && c <= 0xFAFF) ||
                    (c >= 0xFE30 && c <= 0xFE6F) ||
                    (c >= 0xFF00 && c <= 0xFF60) ||
                    (c >= 0xFFE0 && c <= 0xFFE6) ||
                    (c >= 0x20000 && c <= 0x3FFFD))) {
                w += 2
            } else {
                w += 1
            }
        }
        return w
    }

    /** 按显示宽度右补空格 */
    static String padRight(String s, int width) {
        String v = s == null ? '' : s
        StringBuilder sb = new StringBuilder(v)
        int pad = width - displayWidth(v)
        for (int i = 0; i < pad; i++) sb.append(' ')
        return sb.toString()
    }

    /** 按显示宽度左补空格 */
    static String padLeft(String s, int width) {
        String v = s == null ? '' : s
        StringBuilder sb = new StringBuilder()
        int pad = width - displayWidth(v)
        for (int i = 0; i < pad; i++) sb.append(' ')
        sb.append(v)
        return sb.toString()
    }

    /** 重复字符生成分隔线 */
    static String line(int width, String ch) {
        StringBuilder sb = new StringBuilder()
        String c = (ch == null || ch.isEmpty()) ? '-' : ch
        for (int i = 0; i < width; i++) sb.append(c)
        return sb.toString()
    }

    /** 单行状态摘要："✔ SUCCESS" */
    static String statusLabel(String status, boolean color) {
        String s = (status ?: 'UNKNOWN').toUpperCase()
        String text = statusSymbol(s) + ' ' + s
        return colorize(text, STATUS_COLOR.getOrDefault(s, GREY), color)
    }
}
