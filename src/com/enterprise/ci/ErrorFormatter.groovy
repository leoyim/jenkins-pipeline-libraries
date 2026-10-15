package com.enterprise.ci

/**
 * 异常清洗引擎：把 Jenkins 中杂乱的堆栈，整理成
 * "分类 + 人话原因 + 处理建议 + 精简堆栈摘要" 的结构化信息。
 *
 * 纯逻辑、无 pipeline step 依赖，可独立单测。
 */
class ErrorFormatter implements Serializable {

    private static final long serialVersionUID = 1L

    /** 判定时先看消息内容的规则：[regex, category] */
    private static final List<List> MSG_RULES = [
        ['(?i)script returned exit code (\\d+)',                        'TOOL'],
        ['(?i)timed?[- ]?out|timeout',                                  'TIMEOUT'],
        ['(?i)connection (refused|reset|timed out)|unknown host|no route to host|network is unreachable|SSLHandshake|certificate', 'NETWORK'],
        ['(?i)tests? run.*fail|There are test failures',                'TEST'],
        ['(?i)could not (resolve|checkout|clone)|repository not found|authentication failed', 'SCM'],
    ].asImmutable()

    /** 类名前缀 -> [category, hint]；按顺序匹配，第一条命中生效 */
    private static final List<List> CLASS_RULES = [
        ['FlowInterruptedException',                 'ABORT',     '构建被中止：确认是否有人手动停止、并发互斥或超时配置触发'],
        ['java.util.concurrent.TimeoutException',    'TIMEOUT',   '执行超时：检查依赖仓库/网络/执行器负载，或调大该阶段 timeout'],
        ['java.net.UnknownHostException',            'NETWORK',   'DNS 解析失败：检查域名拼写与构建节点 DNS 配置'],
        ['java.net.ConnectException',                'NETWORK',   '连接被拒绝/失败：检查目标端口、服务状态与防火墙策略'],
        ['java.net.SocketTimeoutException',          'NETWORK',   '网络超时：检查仓库/制品库/目标服务连通性与代理设置'],
        ['SSLHandshakeException',                    'NETWORK',   'TLS 握手失败：检查证书链、CA 信任与系统时间'],
        ['hudson.remoting.',                         'INFRA',     '构建节点通信中断：检查 agent 是否在线/重启，重试通常可恢复'],
        ['Channel.ClosedException',                  'INFRA',     '构建节点断连：检查 agent 进程与网络，重试通常可恢复'],
        ['RejectedAccessException',                  'SANDBOX',   '被 Jenkins 沙箱拦截：到 In-process Script Approval 审批，或将逻辑收敛进共享库 src/'],
        ['MissingLibraryException',                  'LIBRARY',   '共享库加载失败：检查 @Library 引用、版本号与 Jenkins 共享库配置'],
        ['groovy.lang.MissingMethodException',       'LIBRARY',   '函数不存在：检查共享库函数名拼写、库版本与参数列表'],
        ['groovy.lang.MissingPropertyException',     'LIBRARY',   '变量不存在：检查函数参数名/属性名拼写'],
        ['hudson.scm.',                              'SCM',       '代码检出失败：检查仓库地址、分支名与凭据配置'],
        ['FileNotFoundException',                    'TOOL',      '文件/工具不存在：检查构建工具安装、路径与产物是否存在'],
        ['PermissionDenied',                         'PERMISSION','权限不足：检查凭据 ID、目标系统账号与目录权限'],
    ].asImmutable()

    /** 堆栈帧过滤前缀（Jenkins/Groovy/JDK 内部帧，无排查价值） */
    private static final List<String> STACK_PREFIXES = [
        'hudson.', 'jenkins.', 'org.jenkinsci.', 'com.cloudbees.', 'org.kohsuke.',
        'workflow.', 'libs.', 'groovy.', 'org.codehaus.groovy.',
        'java.', 'javax.', 'jdk.', 'sun.', 'org.apache.tools.ant.',
    ].asImmutable()

    /**
     * 分析异常。
     * @return [category, reason, hint, exitCode(String 或 null)]
     */
    static Map analyze(Throwable t) {
        if (t == null) {
            return [category: 'UNKNOWN', reason: '未知错误（无异常对象）',
                    hint: '查看完整构建日志定位失败环节', exitCode: null]
        }
        String cls = t.class != null ? t.class.name : ''
        String simple = t.class != null ? t.class.simpleName : 'Throwable'
        String msg = t.message != null ? t.message.toString() : ''
        String root = rootMessage(t)

        String category = 'APP'
        String hint = '应用/业务逻辑异常：查看下方堆栈摘要与完整构建日志'
        String exitCode = null

        // 1) 消息内容规则
        for (List rule : MSG_RULES) {
            def m = (root =~ (String) rule[0])
            if (m.find()) {
                category = (String) rule[1]
                if (category == 'TOOL') {
                    exitCode = m.group(1)
                    hint = '命令执行失败：查看退出码与 sh 命令输出，定位脚本/工具问题'
                }
                break
            }
        }
        // 2) 类名规则（优先级更高）
        for (List rule : CLASS_RULES) {
            if (cls.contains((String) rule[0])) {
                category = (String) rule[1]
                hint = (String) rule[2]
                break
            }
        }
        // 3) AbortException 无退出码时兜底为 TOOL
        if (category == 'APP' && cls.contains('hudson.AbortException')) {
            category = 'TOOL'
            hint = '步骤执行失败：结合上方命令输出定位问题'
        }

        String reason = (root ?: msg ?: simple).trim()
        return [category: category, reason: reason, hint: hint, exitCode: exitCode]
    }

    /** 最深层的非空异常消息（根因） */
    static String rootMessage(Throwable t) {
        Throwable cur = t
        String msg = null
        int depth = 0
        while (cur != null && depth < 10) {
            if (cur.message != null && cur.message.toString().trim()) {
                msg = cur.message.toString().trim()
            }
            Throwable next = cur.cause
            if (next == cur) break
            cur = next
            depth++
        }
        return msg ?: ''
    }

    /** 单行摘要："类名: 消息" */
    static String oneLine(Throwable t) {
        if (t == null) return '未知错误'
        String cls = t.class != null ? t.class.simpleName : 'Throwable'
        String msg = rootMessage(t) ?: (t.message != null ? t.message.toString() : '')
        return msg ? ("${cls}: ${msg}".toString()) : cls
    }

    /** cause 链摘要，最多 depth 层 */
    static List<String> causeChain(Throwable t, int depth = 3) {
        List<String> out = []
        Throwable cur = t != null ? t.cause : null
        int i = 0
        while (cur != null && i < depth) {
            String cls = cur.class != null ? cur.class.simpleName : 'Throwable'
            String msg = cur.message != null ? cur.message.toString() : ''
            out << ("Caused by: ${cls}: ${msg}".toString())
            Throwable next = cur.cause
            if (next == cur) break
            cur = next
            i++
        }
        return out
    }

    /** 精简堆栈：过滤 Jenkins/Groovy/JDK 内部帧，最多 max 帧 */
    static List<String> stackFrames(Throwable t, int max = 5) {
        if (t == null) return []
        StackTraceElement[] frames = t.stackTrace ?: new StackTraceElement[0]
        List<String> picked = []
        for (StackTraceElement f : frames) {
            String cn = f.className != null ? f.className : ''
            boolean internal = false
            for (String p : STACK_PREFIXES) {
                if (cn.startsWith(p)) { internal = true; break }
            }
            if (internal) continue
            picked << ("    at ${f}".toString())
            if (picked.size() >= max) break
        }
        if (picked.isEmpty()) {
            int n = Math.min(max, frames.length)
            for (int i = 0; i < n; i++) picked << ("    at ${frames[i]}".toString())
        }
        return picked
    }
}
