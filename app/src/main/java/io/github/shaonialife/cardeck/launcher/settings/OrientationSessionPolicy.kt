package io.github.shaonialife.cardeck.launcher.settings

/** 方向覆盖只接受创建任务时的代次；实时切换后冻结地图覆盖，下一连接再重新决定。 */
internal class OrientationSessionPolicy(private val isMapPackage: (String) -> Boolean) {
    data class Token(val generation: Long)

    private var generation = 0L
    private var active = false
    private var phoneNavigation: Boolean? = null
    private var mapProtectionFrozen = false

    @Synchronized
    fun begin(protectPhoneMaps: Boolean): Token {
        generation++
        active = true
        phoneNavigation = protectPhoneMaps
        mapProtectionFrozen = false
        return Token(generation)
    }

    /** 切换不能靠重启地图撤销横屏；切回车机后也保留保护，避免中断手机上已有导航。 */
    @Synchronized
    fun updatePhoneNavigation(enabled: Boolean): Token? {
        if (!active) return null
        if (phoneNavigation != enabled) {
            generation++
            phoneNavigation = enabled
            mapProtectionFrozen = true
        }
        return Token(generation)
    }

    /** 重复清理不再换代，保证 reset 排队后接着 detach 仍能完成同一轮清理。 */
    @Synchronized
    fun end(): Token {
        if (active) generation++
        active = false
        return Token(generation)
    }

    @Synchronized
    fun token(): Token = Token(generation)

    @Synchronized
    fun sessionToken(): Token? = if (active) Token(generation) else null

    /** 启动残留检查也会在 Activity 重建时运行，不能为恢复旧覆盖而终止仍活跃的连接。 */
    @Synchronized
    fun idleRecoveryToken(): Token? = if (active) null else Token(generation)

    @Synchronized
    fun isCurrent(token: Token): Boolean = token.generation == generation

    @Synchronized
    fun isActive(token: Token): Boolean = active && token.generation == generation

    @Synchronized
    fun isProtected(pkg: String, fallbackPhoneNavigation: Boolean = false): Boolean =
        (mapProtectionFrozen || if (active) phoneNavigation == true else fallbackPhoneNavigation) &&
            isMapPackage(pkg.substringBefore('/'))

    @Synchronized
    fun permits(token: Token, pkg: String, fallbackPhoneNavigation: Boolean = false): Boolean =
        token.generation == generation && !isProtected(pkg, fallbackPhoneNavigation)

    /** 只复原实际尝试过写入的包；偏好名单不意味着本轮修改过系统。 */
    @Synchronized
    fun resetCandidates(
        token: Token,
        attempted: Set<String>,
        fallbackPhoneNavigation: Boolean = false,
        legacyEnabled: Set<String> = emptySet(),
        recoverLegacy: Boolean = false,
    ): Set<String> =
        if (token.generation != generation) emptySet()
        else (attempted + if (recoverLegacy) legacyEnabled else emptySet())
            .filterTo(linkedSetOf()) { !isProtected(it, fallbackPhoneNavigation) }
}
