package com.enterprise.ci

/**
 * 运行上下文 / 状态机：以 env 为唯一事实来源，负责
 *  - 状态合并（SUCCESS < UNSTABLE < FAILURE < ABORTED，绝不回退）
 *  - 阶段台账（名称/状态/耗时）
 *  - 错误台账（最多保留 3 条，单行清洗）
 *
 * 兼容性说明：
 *  pipeline 里的 env 是 EnvActionImpl（不是 java.util.Map），它只暴露
 *  get(String)/put(String,String)。因此这里：
 *   1) 参数类型用 def 接受 EnvActionImpl 或普通 Map（便于单测）；
 *   2) 读写统一走 envGet/envPut 辅助方法（底层调用 get/put），不依赖 Groovy
 *      属性语法——属性语法在“已编译的 src 类”中对 EnvActionImpl 不可靠。
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

    // ---------------- env 读写适配 ----------------
    private static String envGet(def env, String key) {
        if (env == null) return null
        def v = env.get(key)
        return v == null ? null : v.toString()
    }

    private static void envPut(def env, String key, String value) {
        if (env == null) return
        env.put(key, value == null ? '' : value.toString())
    }

    // ---------------- 状态机 ----------------
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

    static String status(def env) {
        return normalize(envGet(env, 'CI_STATUS'))
    }

    static void mark(def env, String status) {
        envPut(env, 'CI_STATUS', merge(envGet(env, 'CI_STATUS'), status))
    }

    /** 单条错误写入台账（去重、限长、限条数），返回清洗后单行 */
    static String addError(def env, String line) {
        String clean = (line == null ? '' : line).replaceAll(/[\r\n]+/, ' ').replaceAll(/\s+/, ' ').trim()
        if (clean.length() > 300) clean = clean.substring(0, 300) + '...'
        int count = 0
        try { count = (envGet(env, 'CI_ERROR_COUNT') ?: '0').toInteger() } catch (ignore) { count = 0 }
        count++
        envPut(env, 'CI_ERROR_COUNT', String.valueOf(count))

        String prev = envGet(env, 'CI_ERRORS') ?: ''
        if (prev.contains(clean)) return clean
        List<String> items = prev ? prev.split(RE_STAGE).toList() : []
        items << clean
        while (items.size() > MAX_ERRORS) items.remove(0)
        envPut(env, 'CI_ERRORS', items.join(SEP_STAGE))
        return clean
    }

    static void stageEnd(def env, String name, String status, long durationMs) {
        String key = sanitize(name)
        String entry = key + SEP_FIELD + normalize(status) + SEP_FIELD + String.valueOf(durationMs)
        String prev = envGet(env, 'CI_STAGES') ?: ''
        envPut(env, 'CI_STAGES', prev ? (prev + SEP_STAGE + entry) : entry)
    }

    /** @return [[name,status,durationMs], ...] */
    static List<Map> stages(def env) {
        List<Map> out = []
        String raw = envGet(env, 'CI_STAGES') ?: ''
        if (!raw) return out
        for (String item : raw.split(RE_STAGE)) {
            String[] f = item.split(SEP_FIELD)
            if (f.length >= 3) {
                out << [name: f[0], status: f[1], durationMs: f[2] as Long]
            }
        }
        return out
    }

    static List<String> errors(def env) {
        String raw = envGet(env, 'CI_ERRORS') ?: ''
        return raw ? raw.split(RE_STAGE).toList() : []
    }

    static void reset(def env) {
        envPut(env, 'CI_STATUS', 'SUCCESS')
        envPut(env, 'CI_STAGES', '')
        envPut(env, 'CI_ERRORS', '')
        envPut(env, 'CI_ERROR_COUNT', '0')
        envPut(env, 'CI_STAGE_INDEX', '0')
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
