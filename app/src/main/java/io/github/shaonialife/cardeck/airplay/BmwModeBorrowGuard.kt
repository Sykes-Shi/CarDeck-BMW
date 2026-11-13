package io.github.shaonialife.cardeck.airplay

/**
 * BMW 已观测借用/归还的最小保护，每个 AirPlay client 持有独立实例。
 * 这不是完整资源仲裁器：仅补充实测主屏 Borrow → 特定 Take → requestUI 的回切。
 * 其它 Take/Untake 保留原处理；借用期间禁止全占声明覆盖车机。
 * 调用方须用同一把锁串行执行 apply、读取 resources 和发送，防止旧快照抢回已借出的屏幕。
 */
internal class BmwModeBorrowGuard(receiverIdentity: String) {
    private class BorrowState {
        val ids = linkedSetOf<String>()
        var unknown = false
        var permanentOwner = CONTROLLER
        val borrowed: Boolean get() = unknown || ids.isNotEmpty()
        val owner: Long get() = if (borrowed) ACCESSORY else permanentOwner
    }

    private val eligibleReceiver = receiverIdentity.trim().split(Regex("\\s+")).joinToString(" ")
        .equals("BMW Group MGU-50-P", ignoreCase = true)
    private val screen = BorrowState()
    private val audio = BorrowState()
    private var nativePhoneCall = false
    private var nativeSpeech = -1L
    // Even epochs permit media. Odd epochs last until the car acknowledges the return of
    // the audio resource and the end of the native call. The sender reads this lock-free.
    @Volatile private var callEpoch = 0L
    @Volatile private var uiRevision = 0L
    private var screenReturnPending = false
    private var screenNativeReturnArmed = false
    private var deferredMedia: String? = null

    data class UiRestoreDecision(val allowed: Boolean, val reason: String)

    /** 仅返回固定词和有界计数；borrowID、URL、reasonStr 及其他任意文本从不输出。 */
    fun apply(command: Map<*, *>): String {
        fun ignored(reason: String) = "modeBorrowGuard=$reason ${summary()}"
        if (!eligibleReceiver) return ignored("receiver_not_supported")
        if (command["type"] != "changeModes") return ignored("unsupported_command")
        val params = command["params"] as? Map<*, *> ?: return ignored("malformed_params")
        val states = params["appStates"] as? List<*>
        if (states != null && states.size <= MAX_RESOURCE_ROWS) {
            for (entry in states) {
                val row = entry as? Map<*, *> ?: continue
                when (integer(row["appStateID"])) {
                    2L -> (row["state"] as? Boolean)?.let { nativePhoneCall = it }
                    1L -> integer(row["speechMode"])?.takeIf { it == -1L || it in 1L..3L }?.let { nativeSpeech = it }
                }
            }
            if (nativePhoneCall && callEpoch % 2 == 0L) callEpoch++
        }
        if (nativePhoneCall || nativeSpeech > 0) deferredMedia = null
        val rows = params["resources"] as? List<*> ?: return ignored("no_resource_list")
        // 超出处理上限时可能遗漏另一条借用，不能因截断而声称资源已空闲。
        if (rows.size > MAX_RESOURCE_ROWS) {
            uiRevision++
            screen.unknown = true
            audio.unknown = true
            deferredMedia = null
            return ignored("resource_list_overflow_hold")
        }
        var borrowed = 0
        var duplicate = 0
        var returned = 0
        var unmatched = 0
        var held = 0
        var legacy = 0
        var unsupported = 0
        var screenTake = 0
        // 同一命令混合新的借屏时不解释为借屏结束，避免依赖数组顺序抢回倒车画面。
        val screenRows = rows.mapNotNull { it as? Map<*, *> }.filter {
            integer(it["resourceID"]) == 1L
        }
        val hasScreenBorrow = screenRows.any { integer(it["transferType"]) == 3L }
        if (hasScreenBorrow) deferredMedia = null
        if ((screen.borrowed || screen.permanentOwner == ACCESSORY) && screenRows.size > 1 &&
            screenRows.any { integer(it["transferType"]) == 1L }
        ) {
            // 实车只有单条主屏 Take。混合 Take/Unborrow 等操作时数组次序不能决定是否抢屏。
            screen.unknown = true
            screenNativeReturnArmed = false
            held++
        }
        val singleKnownScreenBorrow = screen.ids.size == 1 && !screen.unknown
        if (screenRows.isNotEmpty()) uiRevision++
        for (row in rows) {
            val resource = row as? Map<*, *>
            val state = when (integer(resource?.get("resourceID"))) {
                1L -> screen
                2L -> audio
                else -> null
            }
            if (state == null) {
                unsupported++
                continue
            }
            when (integer(resource?.get("transferType"))) {
                3L -> {
                    val id = token(resource?.get("borrowID"))
                    when {
                        id == null -> {
                            // 已明确是车机借用；缺少可匹配标识时只能保守保持，直至新 client。
                            state.unknown = true
                            held++
                        }
                        id in state.ids -> duplicate++
                        state.ids.size >= MAX_BORROWS_PER_RESOURCE -> {
                            state.unknown = true
                            held++
                        }
                        else -> {
                            state.ids.add(id)
                            borrowed++
                        }
                    }
                }
                4L -> {
                    val id = token(resource?.get("borrowID"))
                    if (id != null && state.ids.remove(id)) returned++ else unmatched++
                    // 不清理 unknown：无法证明损坏/溢出时漏记的借用也已归还。
                }
                1L -> {
                    if (state === screen && singleKnownScreenBorrow && !hasScreenBorrow && isObservedScreenTake(resource) &&
                        !screen.unknown && screen.ids.size == 1
                    ) {
                        // 2026-11-06 实车：倒车 Borrow 后以 500/500/100 Take 切回原生界面，
                        // 并未另发 Unborrow。此时只结束临时借用；必须等后续 requestUI 才交还手机。
                        screen.ids.clear()
                        screen.permanentOwner = ACCESSORY
                        screenNativeReturnArmed = true
                        screenTake++
                    } else {
                        if (state === screen && screen.permanentOwner == ACCESSORY &&
                            (!isObservedScreenTake(resource) || screen.borrowed)
                        ) {
                            // 不支持的新约束会覆盖此前回切依据；不能沿用旧的 500 授权越过新限制。
                            screenNativeReturnArmed = false
                        }
                        legacy++
                    }
                }
                2L -> {
                    if (state === screen && screen.permanentOwner == ACCESSORY) screenNativeReturnArmed = false
                    legacy++
                }
                else -> {
                    // 原生占屏后收到新的不明主屏操作时，不能沿用此前已观测 Take 的恢复依据。
                    if (state === screen && screen.permanentOwner == ACCESSORY) screenNativeReturnArmed = false
                    unsupported++
                }
            }
        }
        if (screen.unknown || audio.borrowed) deferredMedia = null
        return "modeBorrowGuard=processed borrowed=$borrowed duplicate=$duplicate returned=$returned " +
            "unmatched=$unmatched held=$held retained_legacy=$legacy unsupported=$unsupported " +
            "screenTake=$screenTake ${summary()}"
    }

    /** 借用改变临时 entity；实测特定 Take 保持原生永久占有，不立即显示手机画面。 */
    fun resources(): List<Map<String, Any?>> = listOf(
        linkedMapOf("resourceID" to 1L, "entity" to screen.owner, "permanentEntity" to screen.permanentOwner),
        linkedMapOf("resourceID" to 2L, "entity" to audio.owner, "permanentEntity" to CONTROLLER),
    )

    fun isScreenBorrowed(): Boolean = screen.borrowed

    fun isAudioBorrowed(): Boolean = audio.borrowed

    fun appStates(): List<Map<String, Any?>> = listOf(
        linkedMapOf("appStateID" to 2L, "entity" to if (nativePhoneCall) ACCESSORY else 0L),
        linkedMapOf("appStateID" to 3L, "entity" to 0L),
        linkedMapOf("appStateID" to 1L, "entity" to if (nativeSpeech > 0) ACCESSORY else 0L, "speechMode" to nativeSpeech),
    )

    fun mediaEpoch(): Long? = callEpoch.takeIf { it % 2 == 0L }

    /** Called under the caller's mode lock only after the current declaration got RTSP 200. */
    fun acknowledgeModes() {
        if (!nativePhoneCall && !audio.borrowed && callEpoch % 2 != 0L) callEpoch++
    }

    fun isScreenAvailableToController(): Boolean = screen.owner == CONTROLLER

    fun captureUiRestoreRevision(): Long = uiRevision

    // A lone, identified screen loan can be restated to the car. It remains the
    // owner even if the driver presses MEDIA or the phone's return button.
    fun canRetryScreenLoan(revision: Long): Boolean =
        eligibleReceiver && revision == uiRevision && !nativePhoneCall && nativeSpeech <= 0L &&
            !audio.borrowed && !screen.unknown && screen.ids.size == 1 && screen.permanentOwner == CONTROLLER

    fun rememberMedia(token: String?) {
        deferredMedia = token?.takeIf { canRetryScreenLoan(uiRevision) || screenNativeReturnArmed && !screen.borrowed &&
            !nativePhoneCall && nativeSpeech <= 0L && !audio.borrowed }
    }

    fun takeDeferredMedia(revision: Long): String? {
        if (screenRefreshBlock(revision) != null) return null
        return deferredMedia.also { deferredMedia = null }
    }

    /** Called after every changeModes, including appStates-only call-end notifications. */
    fun consumeScreenReturn(wasWithController: Boolean): Long? {
        if (!isScreenAvailableToController()) screenReturnPending = false
        else if (!wasWithController) screenReturnPending = true
        if (!screenReturnPending || screenRefreshBlock(uiRevision) != null) return null
        screenReturnPending = false
        return uiRevision
    }

    /** A refresh redraws only a screen already released by the head unit. */
    fun screenRefreshBlock(expectedRevision: Long): String? = when {
        !eligibleReceiver -> "receiver_not_supported"
        expectedRevision != uiRevision -> "mode_state_changed"
        !isScreenAvailableToController() -> "receiver_owns_screen"
        nativePhoneCall || nativeSpeech > 0L || audio.borrowed -> "native_activity_active"
        else -> null
    }

    fun controllerInputRevision(): Long? = if (eligibleReceiver && isScreenAvailableToController()) uiRevision else null

    /** 仅在车机 requestUI 到达后调用；排队期间有新主屏事件时不得使用旧请求恢复。 */
    fun restoreRequestedUi(expectedRevision: Long, refreshLoan: Boolean = false): UiRestoreDecision {
        if (expectedRevision != uiRevision) return UiRestoreDecision(false, "mode_state_changed")
        if (screen.borrowed) {
            return UiRestoreDecision(false, if (refreshLoan && canRetryScreenLoan(expectedRevision)) "awaiting_screen_return" else "receiver_screen_borrowed")
        }
        if (screen.permanentOwner == ACCESSORY) {
            if (nativePhoneCall || nativeSpeech > 0L) return UiRestoreDecision(false, "native_activity_active")
            if (!screenNativeReturnArmed) return UiRestoreDecision(false, "native_screen_restore_not_supported")
            // 只有上述本车已观测参数的 Take 能进入此状态，takeConstraint=500 允许用户请求回切。
            screen.permanentOwner = CONTROLLER
            screenNativeReturnArmed = false
            uiRevision++
            return UiRestoreDecision(true, "bmw_take_then_request_ui")
        }
        return UiRestoreDecision(true, "current_modes")
    }

    fun summary(): String =
        "screenOwner=${screen.owner} screenActive=${screen.ids.size} screenUnknown=${screen.unknown} " +
            "screenPermanent=${screen.permanentOwner} nativeReturnArmed=$screenNativeReturnArmed uiRevision=$uiRevision " +
            "audioOwner=${audio.owner} audioActive=${audio.ids.size} audioUnknown=${audio.unknown}"

    private fun isObservedScreenTake(resource: Map<*, *>?): Boolean =
        resource != null && integer(resource["transferPriority"]) == 500L &&
            integer(resource["takeConstraint"]) == 500L && integer(resource["borrowConstraint"]) == 100L &&
            !resource.containsKey("borrowID") && !resource.containsKey("unborrowConstraint")

    private fun token(value: Any?): String? = (value as? String)?.takeIf {
        it.length in 1..MAX_BORROW_ID_LENGTH && !it.isBlank() && it.none(Char::isISOControl)
    }

    // BinaryPlist 的枚举值为整数；不把浮点数、字符串、布尔值隐式截断成有效资源请求。
    private fun integer(value: Any?): Long? = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private companion object {
        const val CONTROLLER = 1L
        const val ACCESSORY = 2L
        const val MAX_BORROW_ID_LENGTH = 128
        const val MAX_BORROWS_PER_RESOURCE = 8
        const val MAX_RESOURCE_ROWS = 32
    }
}
