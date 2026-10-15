package com.enterprise.ci

/**
 * 配置中心：默认值 + 深度合并 + 校验。
 * 所有共享库函数的入参都经过这里，保证"用户只传关心的字段"。
 */
class CiConfig implements Serializable {

    private static final long serialVersionUID = 1L

    static final List<String> BUILD_TOOLS = ['mock', 'maven', 'gradle', 'npm'] as List<String>
    static final List<String> DEPLOY_MODES = ['mock', 'ssh', 'curl', 'k8s'] as List<String>

    /** 全局默认配置（可被 ciInit(cfg) / 各函数入参覆盖） */
    static Map defaults() {
        return [
            app       : 'demo-app',    // 应用名
            env       : 'staging',     // 目标环境
            version   : null,          // 版本号，null 时自动解析
            simulate  : true,          // 全局模拟开关（true=演示/联调安全模式）
            color     : 'auto',        // auto|true|false 日志颜色
            build: [
                tool      : 'mock',    // mock|maven|gradle|npm
                goals     : 'clean package',
                args      : '',
                toolName  : 'maven-3.9', // Jenkins 全局工具配置名（真实模式）
                outputDir : 'target',
                archive   : true,
                mockDelay : 1,         // 模拟耗时（秒）
            ],
            docker: [
                enabled   : true,
                mode      : 'mock',    // mock|real
                registry  : 'registry.internal',
                namespace : null,      // 默认取 app
                dockerfile: 'Dockerfile',
                context   : '.',
            ],
            deploy: [
                mode      : 'mock',    // mock|ssh|curl|k8s
                target    : null,      // 默认取 env
                image     : null,      // 默认取 docker 产出
                replicas  : 2,
                strategy  : 'rolling',
                host      : '127.0.0.1',
                namespace : 'demo',
                script    : '',
                gate      : 'none',    // none|manual（manual 时人工确认）
            ],
            verify: [
                enabled : true,
                url     : 'http://localhost:8080/actuator/health',
                attempts: 5,
                interval: 2,           // 秒
                expect  : 200,
                mock    : null,        // null=跟随 simulate
            ],
            notify: [
                enabled: true,
                channel: 'mock',       // mock|webhook|email
                webhook: '',
                on     : 'always',     // always|failure
            ],
        ]
    }

    /** 递归深合并：override 优先；Map 递归合并，其余类型整体替换 */
    static Map deepMerge(Map base, Map override) {
        Map out = new LinkedHashMap(base ?: [:])
        if (override == null || override.isEmpty()) return out
        override.each { k, v ->
            if (v == null) return
            if (v instanceof Map && out[k] instanceof Map) {
                out[k] = deepMerge((Map) out[k], (Map) v)
            } else {
                out[k] = v
            }
        }
        return out
    }

    /** 多层配置依次合并 */
    static Map resolve(List<Map> layers) {
        Map out = [:]
        for (Map layer : layers) out = deepMerge(out, layer)
        return out
    }

    /** 校验：不合法时抛出带清洗说明的异常（会被 ErrorFormatter 归类为配置错误） */
    static Map validate(Map cfg) {
        if (cfg.build != null && cfg.build.tool != null && !BUILD_TOOLS.contains(cfg.build.tool)) {
            throw new IllegalArgumentException(
                "不支持的构建工具 build.tool='${cfg.build.tool}'，可选值: ${BUILD_TOOLS.join('/')}")
        }
        if (cfg.deploy != null && cfg.deploy.mode != null && !DEPLOY_MODES.contains(cfg.deploy.mode)) {
            throw new IllegalArgumentException(
                "不支持的部署方式 deploy.mode='${cfg.deploy.mode}'，可选值: ${DEPLOY_MODES.join('/')}")
        }
        if (cfg.verify != null && cfg.verify.attempts != null && (cfg.verify.attempts as Integer) < 1) {
            throw new IllegalArgumentException('verify.attempts 必须 >= 1')
        }
        return cfg
    }

    static boolean truthy(Object v) {
        if (v == null) return false
        String s = v.toString().trim().toLowerCase()
        return ['true', '1', 'yes', 'y', 'on'].contains(s)
    }
}
