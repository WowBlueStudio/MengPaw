// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

/**
 * 完整性保护的全局注册点 (kernel 级)。
 *
 * 背景: 路径级写保护 (IntegrityGuard) 此前只挂在 [com.mengpaw.kernel.cli.Pipeline] 上 —
 * Pipeline 只处理**注册表命令**, 而 v0.36.x 去重后文件写删走 Linux 命令通道
 * ([com.mengpaw.kernel.cli.LinuxCommandExecutor]), 完全不经 Pipeline,
 * 于是 `cp/mv/tee/sed -i` 之类的核心目录写操作没有任何路径级拦截 (保护链路断裂)。
 *
 * 本对象把宿主注入的 [IntegrityProvider] 提升为 kernel 级共享实例:
 * Pipeline 与 Linux 命令通道同一来源, 两条通道共用同一套保护判定。
 * 未注入时保持 [NoOpIntegrityProvider] (纯 JVM 测试/桌面场景零影响)。
 *
 * **能力边界 (如实标注)**: 判定基于**绝对路径参数** — 相对路径参数不参与判定
 * (工作区内的相对路径是合法可写区)。因此 `cd <受保护目录>` 后再用相对路径写入,
 * 不构成路径级拦截 (仍受 Android 应用沙箱约束, 不越出应用私有目录之外)。
 * 若后续需要覆盖, 应在 Linux 通道按 `ctx.workDir` 解析相对路径后再判定。
 */
object SecurityGate {

    @Volatile
    var integrityProvider: IntegrityProvider = NoOpIntegrityProvider

    /** 宿主注入 (Android 侧 IntegrityGuard.globalInstance); 传 null 表示退回 no-op。 */
    fun register(provider: IntegrityProvider?) {
        integrityProvider = provider ?: NoOpIntegrityProvider
    }

    /** 校验一条命令是否触碰受保护路径; null = 放行, 非 null = 拦截原因。 */
    fun validate(commandName: String, args: List<String>): String? =
        integrityProvider.validateCommand(commandName, args)
}
