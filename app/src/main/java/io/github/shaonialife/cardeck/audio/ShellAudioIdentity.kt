package io.github.shaonialife.cardeck.audio

/** 输出采音只使用本进程真实的 shell 身份；资源 Context 的名称不能代替 UID 验证。 */
internal object ShellAudioIdentity {
    const val UID = 2000
    const val PACKAGE = "com.android.shell"

    fun validationFailure(
        processUid: Int,
        installedPackageName: String?,
        installedPackageUid: Int,
    ): String? =
        when {
            processUid != UID -> "unsupported_uid"
            installedPackageName != PACKAGE -> "invalid_shell_package"
            installedPackageUid != processUid -> "invalid_shell_package_uid"
            else -> null
        }
}
