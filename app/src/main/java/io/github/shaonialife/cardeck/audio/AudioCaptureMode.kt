package io.github.shaonialife.cardeck.audio

/** 车机声音统一使用系统输出采集；仅保留诊断标识，不提供运行时模式选择。 */
object AudioCaptureMode {
    const val diagnosticName = "system_output"
    const val label = "车机声音"
}
