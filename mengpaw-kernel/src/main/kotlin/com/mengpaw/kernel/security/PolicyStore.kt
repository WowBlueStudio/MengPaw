// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

/**
 * 全局安全策略持有器 + 持久化 (自检报告 P1-7)。
 *
 * Pipeline 默认构造参数与 agent.policy 命令共用同一实例 — agent.policy 授权即刻生效,
 * 无需重启管线; 授权表持久化到 {BASE}/配置/policy.json, 首次访问懒加载恢复 (启动可恢复,
 * 无需改 AgentEngine 启动路径)。
 */
object PolicyStore {
    private val lock = Any()
    @Volatile private var loaded = false

    /** 策略持久化文件 — 默认 {BASE}/配置/policy.json; 测试可指向临时文件。 */
    @Volatile var policyFile: java.io.File = java.io.File(com.mengpaw.kernel.DataPaths.CONFIG, "policy.json")

    /** 全局共享策略 — Pipeline 默认参数与 agent.policy 命令共用 (经 [sharedPolicy] 懒加载)。 */
    @Volatile var shared: SecurityPolicy = SecurityPolicy()
        private set

    /**
     * 获取共享策略 — 首次访问时从 [policyFile] 恢复持久化授权 (幂等)。
     * Pipeline 默认构造参数 = PolicyStore.sharedPolicy()。
     */
    fun sharedPolicy(): SecurityPolicy {
        if (!loaded) synchronized(lock) {
            if (!loaded) {
                shared.loadFrom(policyFile)
                loaded = true
            }
        }
        return shared
    }

    /** 持久化当前授权表 — 原子写。@return 是否写入成功。 */
    fun save(): Boolean = sharedPolicy().saveTo(policyFile)

    /** 测试隔离用 — 替换为独立实例并指向临时文件。 */
    fun resetForTest(instance: SecurityPolicy, file: java.io.File) {
        synchronized(lock) {
            shared = instance
            policyFile = file
            loaded = false
        }
    }
}
