# jenkins-pipeline-libraries — 企业级 Jenkins 共享库

面向企业级持续集成/发版活动的共享库：**业务方不需要从 0 编写脚本**，大部分活动通过库中函数完成，
同时保证 **日志醒目、异常清洗、状态同步正确、方法易用**。

## 目录结构

```
jenkins-share-lib/
├── Jenkinsfile                    # 库自检 Pipeline（模拟跑一遍全流程）
├── vars/                          # 公共函数（用户 API，全局变量）
│   ├── ciPipeline.groovy          #   一键编排：初始化→构建→镜像→部署→验证→汇总→通知
│   ├── ciInit.groovy              #   初始化：配置合并、状态重置、运行摘要
│   ├── ciStage.groovy             #   阶段包装：醒目头尾 + timeout/retry + 异常清洗 + 失败即停
│   ├── ciTry.groovy               #   错误捕获原语：清洗日志 + 状态同步 + 可选向上抛
│   ├── ciLog.groovy               #   统一日志：banner / kv 表 / 分级标签 / 可选 ANSI 颜色
│   ├── ciBuild.groovy             #   构建能力：mock / maven / gradle / npm
│   ├── ciDockerBuild.groovy       #   镜像构建：mock / real（build + push）
│   ├── ciDeploy.groovy            #   部署能力：mock / ssh / curl / k8s（支持人工闸门）
│   ├── ciVerify.groovy            #   部署后验证：健康检查轮询
│   ├── ciNotify.groovy            #   通知：mock 卡片 / webhook / email
│   └── ciReport.groovy            #   结果汇总：状态同步 + 阶段台账表 + 错误摘要
├── src/com/enterprise/ci/         # 纯逻辑类（不依赖 pipeline step，可独立单测）
│   ├── Ansi.groovy                #   ANSI 颜色 / Unicode 符号 / 中文宽度对齐
│   ├── ErrorFormatter.groovy      #   异常清洗引擎（分类/人话原因/建议/精简堆栈）
│   ├── RunContext.groovy          #   状态机 + 阶段/错误台账（env 为唯一事实来源）
│   └── CiConfig.groovy            #   配置默认值 + 深度合并 + 校验
└── examples/
    ├── Jenkinsfile.full           # 全量显式调用示例（模拟发版构建+部署，可直接运行）
    ├── Jenkinsfile.simple         # 极简示例（一行 ciPipeline）
    └── Jenkinsfile.declarative    # 声明式 Pipeline 集成示例
```

## 安装

1. **推送到 Git 仓库**（本目录整体作为一个库仓库）。
2. **Jenkins 全局库配置**：Manage Jenkins → System → *Global Trusted Pipeline Libraries*
   - Name：`jenkins-share-lib`（业务 Jenkinsfile 里 `@Library('jenkins-share-lib')` 与此一致）
   - Default version：`master`（或固定 tag，企业建议打 tag）
   - Retrieval：Modern SCM → Git，指向本仓库。
3. **验证**：新建 Pipeline 任务，Pipeline script 选本仓库根目录的 `Jenkinsfile` 运行一次，
   看到「构建汇总」即表示库可用。

可选：安装 **AnsiColor** 插件后在 Jenkinsfile 中 `options { ansiColor('xterm') }`，
并设置 `color: 'true'` 开启彩色日志；未安装时默认纯文本，日志依然通过符号/对齐/分隔线保持醒目。

## 快速开始

**极简（一行完成模拟发版）：**

```groovy
@Library('jenkins-share-lib') _

node {
    ciPipeline(app: 'order-api', env: 'staging', simulate: true)
}
```

**显式分阶段（可按需裁剪）：**

```groovy
@Library('jenkins-share-lib') _

node {
    try {
        ciInit(app: 'order-api', env: 'staging', simulate: true)
        ciStage('拉取源码')          { checkout scm }
        ciStage('编译构建', timeoutMinutes: 30) { ciBuild(tool: 'mock') }
        ciStage('镜像构建')          { ciDockerBuild(registry: 'registry.internal') }
        ciStage('部署发布')          { ciDeploy(target: 'staging', replicas: 2) }
        ciStage('部署验证')          { ciVerify(attempts: 3) }
    } finally {
        ciReport()   // 状态同步 + 汇总
        ciNotify()   // 通知
    }
}
```

完整可运行版本见 `examples/Jenkinsfile.full`（含参数化、失败注入演示、生产人工闸门）。

## 函数手册

所有函数均支持**扁平参数**与**分组参数**两种写法，例如
`ciBuild(tool: 'maven')` 等价于 `ciBuild(build: [tool: 'maven'])`；
未传的字段自动取 `ciInit` 全局配置 → 库默认值。

| 函数 | 说明 | 常用参数 |
|---|---|---|
| `ciPipeline(cfg)` | 一键编排全流程，内部自动 init/report/notify | `app` `env` `simulate` `stages`(自定义阶段) `build/docker/deploy/verify/notify` |
| `ciInit(cfg)` | 初始化：状态重置、版本解析、运行摘要 | `app` `env` `version` `simulate` `color` |
| `ciStage(name, opts) { }` | 阶段包装：头尾横幅、timeout/retry、失败即停 | `timeoutMinutes` `retry` `propagate:false`(失败继续) `logStack:false` |
| `ciTry(opts) { }` | 错误捕获原语（一般由 ciStage 内部使用） | `context` `propagate` `logStack` |
| `ciLog.*` | `info/warn/error/ok/debug/step/banner/kv/raw/rule` | 多行自动缩进续行 |
| `ciBuild(cfg)` | 构建：mock / maven / gradle / npm | `tool` `goals` `args` `toolName`(Jenkins 工具名) `outputDir` `archive` |
| `ciDockerBuild(cfg)` | 镜像构建，成功写入 `env.CI_IMAGE` | `registry` `namespace` `dockerfile` `push` `mode` `enabled` |
| `ciDeploy(cfg)` | 部署：mock / ssh / curl / k8s | `mode` `target` `image` `replicas` `strategy` `host` `namespace` `url` `gate` |
| `ciVerify(cfg)` | 部署后健康检查轮询 | `url` `attempts` `interval` `expect` `enabled` |
| `ciNotify(cfg)` | 通知（失败绝不影响构建结果） | `channel`(mock/webhook/email) `webhook` `on`(always/failure) `to` |
| `ciReport(cfg)` | 汇总 + **末次状态同步**，`post{always}` 或 `finally` 中调用 | — |

## 日志规范

固定宽度标签 `[INFO ] [WARN ] [ERROR] [ OK ] [STEP ]`，Unicode 符号区分级别，
续行自动缩进 8 栗对齐；`banner/kv/stageHeader` 保证关键信息一眼可见：

```
════════════════════════════════════════════════════════════════
★ 发版流水线 order-api 1a2b3c4
════════════════════════════════════════════════════════════════
  应用     : order-api
  版本     : 1a2b3c4
  目标环境 : staging

────────────────────────────────────────────────────────────────
▶ 阶段 [2/5] 编译构建
────────────────────────────────────────────────────────────────
[STEP ] ▸ 构建 order-api 1a2b3c4（模式：模拟）
[ OK ] ✔ 模拟构建完成：target/order-api-1a2b3c4.jar
✔ 阶段「编译构建」结束 · 状态 ✔ SUCCESS · 耗时 3s
```

- 颜色：默认关闭；安装 AnsiColor 插件 + Jenkinsfile `options { ansiColor('xterm') }` +
  `ciInit(color: 'true')`（或 `env.CI_LOG_COLOR='true'`）开启。
- 调试日志：`env.CI_DEBUG='true'` 时输出 `[DEBUG ]` 级别。

## 异常清洗

原始 Jenkins 堆栈（几十行、混杂内部帧）被整理为 **分类 + 人话原因 + 处理建议 + 精简堆栈**：

```
[ERROR] ✖ 上下文「编译构建」执行失败 · 分类=TOOL
[ERROR] ✖ 原因：script returned exit code 42
[ERROR] ✖ 建议：命令执行失败：查看退出码与 sh 命令输出，定位脚本/工具问题
[ERROR] ✖ 退出码：42
[DEBUG ] · 堆栈摘要（已过滤 Jenkins/Groovy/JDK 内部帧）：
        at ...
```

分类规则（`src/.../ErrorFormatter.groovy` 可按企业情况扩展）：

| 分类 | 触发场景 | 示例建议 |
|---|---|---|
| `ABORT` | 手动中止 / 超时中断 | 保留 ABORTED 语义，检查是否有人停止或 timeout 配置 |
| `TIMEOUT` | 执行超时 | 调大 timeout、检查依赖仓库/网络 |
| `NETWORK` | 连接拒绝/DNS/TLS | 检查仓库、制品库、目标服务连通性 |
| `INFRA` | 构建节点断连 | 检查 agent 在线状态，重试通常可恢复 |
| `SANDBOX` | 脚本被沙箱拦截 | Script Approval 审批或将逻辑收敛进共享库 src/ |
| `LIBRARY` | 共享库函数/变量拼写错误 | 检查函数名、库版本与参数 |
| `SCM` | 代码检出失败 | 检查仓库地址、分支名与凭据 |
| `TOOL` | 命令非 0 退出 / 文件不存在 | 提取退出码，结合命令输出定位 |
| `TEST` | 单元测试失败 | 查看测试报告定位失败用例 |
| `PERMISSION` | 权限/凭据不足 | 检查凭据 ID 与目标系统账号权限 |
| `APP` | 其余业务异常 | 查看堆栈摘要与完整日志 |

**抛出策略**：`FlowInterruptedException`（中止）原样上抛保住 ABORTED 状态；
其余异常清洗后以 `AbortException` 上抛 —— Jenkins 只打印一行 `ERROR:`，
不会用原始堆栈刷屏。`propagate: false` 时仅记录失败，流水线继续。

## 状态同步规则

状态严重级别：`SUCCESS < UNSTABLE < FAILURE < ABORTED`，**只升不降**。

1. `ciInit`：`currentBuild.currentResult = 'SUCCESS'`，重置台账，设置 displayName/description。
2. 任何异常在 `ciTry` 捕获的瞬间：`RunContext.mark(env, 状态)` + `currentBuild.currentResult` 同步，
   并写入错误台账（去重、单行、最多 3 条）。
3. `ciStage` 的 `finally` 把 **阶段名/状态/耗时** 写入阶段台账（异常路径也保证写入）。
4. `ciReport`（`post always` / `finally`）：取「台账状态 ∨ Jenkins 已有状态」的更严重者作为最终结果，
   绝不把失败改写成成功；并刷新任务列表的 displayName/description。
5. 中止（Abort）不被 `error()` 转成 FAILURE，保留 `ABORTED`。

## 模拟 vs 真实

| 能力 | 模拟（默认，SIMULATE=true） | 真实（SIMULATE=false） |
|---|---|---|
| 构建 | 分步打印 + 生成模拟 jar 产物 | `tool(name:'maven-3.9')` + `mvn -B ...` |
| 镜像 | 打印镜像坐标/元信息 | `docker build` + `docker push` |
| 部署 | 滚动发布步骤模拟输出 | `ssh` / `curl` 接口 / `kubectl set image + rollout status` |
| 验证 | 模拟 HTTP 200 | `curl` 轮询健康检查直到 `expect` |

真实发版示例：

```groovy
ciPipeline(
    app: 'order-api', env: 'prod', simulate: false,
    build  : [tool: 'maven', goals: 'clean verify', toolName: 'maven-3.9'],
    docker : [registry: 'harbor.example.com', push: true],
    deploy : [mode: 'k8s', namespace: 'prod', gate: 'manual'],
    notify : [channel: 'webhook', webhook: 'https://qyapi.weixin.qq.com/...'],
)
```

> 真实凭据请使用 Jenkins Credentials（`withCredentials`），不要把 token 写进库或 Jenkinsfile。

## 常见问题

- **日志里出现 `[0;32m` 之类的乱码**：未安装 AnsiColor 插件却开了颜色 —— 保持默认
  `color: 'auto'`，或安装插件后 `options { ansiColor('xterm') }`。
- **`no method: input` / `mail` 等步骤报错**：对应插件未安装（`input` 属 pipeline-input-step；
  `mail` 需配置邮件服务器）。按需关闭 `gate` / 改用 `webhook` 通知。
- **`RejectedAccessException`**：逻辑用了未审批的系统步骤 —— 到 In-process Script Approval 审批，
  或把逻辑沉淀进 `src/`（src 中的类不受沙箱限制）。
- **多分支流水线里 `checkout scm` 报错**：声明式流水线已自动检出，可删掉该调用或忽略重复检出。
- **并发互斥**：在 Jenkinsfile 加 `disableConcurrentBuilds()`（示例已带），
  或用 `lock(resource: 'deploy-prod')` 包住部署阶段。

## 扩展指南

- **新增能力函数**：在 `vars/` 加 `ciXxx.groovy`（实现 `call(Map cfg = [:])`），
  复用 `lift/envLayer` 模式接入配置体系，失败场景抛异常让 `ciStage` 统一清洗。
- **新增异常分类**：编辑 `src/com/enterprise/ci/ErrorFormatter.groovy` 的 `CLASS_RULES` / `MSG_RULES`。
- **调整默认配置**：编辑 `src/com/enterprise/ci/CiConfig.groovy` 的 `defaults()`。
- **自定义阶段编排**：`ciPipeline(stages: ['代码扫描': { sh 'sonar-scanner' }, ...])`。
