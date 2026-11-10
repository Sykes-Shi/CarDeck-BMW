package io.github.shaonialife.cardeck.launcher

/** 自动巡航需要明确的空任务证据；失败、未知格式和已有页面都不自动覆盖。 */
internal data class BaiduCruiseTaskEvidence(val querySucceeded: Boolean, val existingTaskCount: Int)

internal fun baiduCruiseTaskEvidence(commandSucceeded: Boolean, output: String): BaiduCruiseTaskEvidence {
    val recognizable = output.contains("ACTIVITY MANAGER ACTIVITIES") &&
        Regex("(?:Display #\\d+|Display: mDisplayId=\\d+)").containsMatchIn(output)
    if (!commandSucceeded || !recognizable) return BaiduCruiseTaskEvidence(false, -1)
    // 同时检查手机和全部虚拟屏；宁可保留已有地图页面，也不把未识别的任务误当空。
    return BaiduCruiseTaskEvidence(true, if (output.contains("com.baidu.BaiduMap")) 1 else 0)
}
