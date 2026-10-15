package com.enterprise.ci

/**
 * 运行上下文 / 状态机：以 env 为唯一事实来源（不依赖 pipeline step），
 * 负责：
 *  - 状态合并（SUCCESS < UNSTABLE < FAILURE < ABORTED，绝不回退）
 *  - 阶段台账（名称/状态/耗时）
 *  - 错误台账（最多保留 3 条，单行清洗）
 */
class RunContext implements Serializable {

    private static final long serialVersionUID = 1L

    private static final Map<String, Integer> RANK = [
        SUCCESS: 0, UNSTABLE: 1, FAILURE: 2, ABORTED: 3, NOT_BUILT: 3,
    ].asImmutable()

    static final String SEP_STAGE = '|'
    // split 的参数是正则，'|' 需转义（Pattern.quote），否则会被当成空分支匹配
    private static final String RE_STAGE = java.util.regex.Pattern.quote(SEP_STAGE)
    private static final String SEP_FIELD = '~'
    private static final int MAX_ERRORS = 3

    static String normalize(String s) {
        String v = s == null ? '' : s.trim().toUpperCase()
        return RANK.containsKey(v) ? v : 'SUCCESS'
    }

    /** 合并两个状态，取更严重者；绝不把 FAILURE 降级为 SUCCESS */
    static String merge(String a, String b) {
        String x = normalize(a)
        String y = normalize(b)
        return RANK.get(x) >= RANK.get(y) ? x : y
    }

    static String status(Map env) {
        return normalize((String) env.CI_STATUS)
    }

    static void mark(Map env, String status) {
        env.CI_STATUS = merge((String) env.CI_STATUS, status)
    }

    /** 单条错误写入台账（自动去重、限长、限条数），返回清洗后单行 */
    static String addError(Map env, String line) {
        String clean = (line == null ? '' : line).replaceAll(/[\r\n]+/, ' ').replaceAll(/\s+/, ' ').trim()
        if (clean.length() > 300) clean = clean.substring(0, 300) + '...'
        int count = 0
        try { count = ((String) env.CI_ERROR_COUNT ?: '0').toInteger() } catch (ignore) { count = 0 }
        count++
        env.CI_ERROR_COUNT = String.valueOf(count)

        String prev = (String) env.CI_ERRORS ?: ''
        if (prev.contains(clean)) return clean
        List<String> items = prev ? prev.split(RE_STAGE).toList() : []
        items << clean
        while (items.size() > MAX_ERRORS) items.remove(0)
        env.CI_ERRORS = items.join(SEP_STAGE)
        return clean
    }

    static void stageEnd(Map env, String name, String status, long durationMs) {
        String key = sanitize(name)
        String entry = key + SEP_FIELD + normalize(status) + SEP_FIELD + String.valueOf(durationMs)
        String prev = (String) env.CI_STAGES ?: ''
        env.CI_STAGES = prev ? (prev + SEP_STAGE + entry) : entry
    }

    /** @return [[name,status,durationMs], ...] */
    static List<Map> stages(Map env) {
        List<Map> out = []
        String raw = (String) env.CI_STAGES ?: ''
        if (!raw) return out
        for (String item : raw.split(RE_STAGE)) {
            String[] f = item.split(SEP_FIELD)
            if (f.length >= 3) {
                out << [name: f[0], status: f[1], durationMs: f[2] as Long]
            }
        }
        return out
    }

    static List<String> errors(Map env) {
        String raw = (String) env.CI_ERRORS ?: ''
        return raw ? raw.split(RE_STAGE).toList() : []
    }

    static void reset(Map env) {
        env.CI_STATUS = 'SUCCESS'
        env.CI_STAGES = ''
        env.CI_ERRORS = ''
        env.CI_ERROR_COUNT = '0'
        env.CI_STAGE_INDEX = '0'
    }

    /** 毫秒 -> "1m 03s" / "850ms" */
    static String humanDuration(long ms) {
        if (ms < 1000) return ms + 'ms'
        long s = ms / 1000L
        if (s < 60) return s + 's'
        long m = s / 60
        long rem = s % 60
        return m + 'm ' + String.format('%02ds', rem)
    }

    private static String sanitize(String name) {
        String v = (name == null ? 'stage' : name).replaceAll(/[|~\r\n]+/, '-').trim()
        if (v.length() > 40) v = v.substring(0, 40)
        return v ?: 'stage'
    }
}
