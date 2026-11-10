package io.github.shaonialife.cardeck.launcher

/**
 * 每次连接创建新实例；已存在的地图任务可能正在导航，自动入口不得覆盖其页面。
 * 查询失败保持原地图启动流程。占位在尝试前完成，失败后也不反复拉起巡航。
 */
class BaiduCruiseSessionPolicy {
    private var attempted = false

    @Synchronized
    fun tryClaimAutoStart(
        enabled: Boolean,
        defaultPackage: String?,
        taskQuerySucceeded: Boolean,
        existingTaskCount: Int,
    ): Boolean {
        if (
            attempted ||
            !enabled ||
            defaultPackage != BaiduCruise.PACKAGE ||
            !taskQuerySucceeded ||
            existingTaskCount != 0
        ) {
            return false
        }
        attempted = true
        return true
    }
}
