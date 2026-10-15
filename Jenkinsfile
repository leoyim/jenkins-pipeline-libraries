// 共享库自检 Pipeline：把本文件作为 Jenkinsfile 的任务运行一次，
// 验证库加载、函数调用、状态同步是否正常（全程模拟，无副作用）。
@Library('lib') _

node {
    ciPipeline(app: 'share-lib-selftest', env: 'dev', simulate: true)
}
