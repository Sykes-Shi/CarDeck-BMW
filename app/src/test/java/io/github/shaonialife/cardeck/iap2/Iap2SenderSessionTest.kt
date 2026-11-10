package io.github.shaonialife.cardeck.iap2

import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.InMemoryStreamPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 发送端会话对 mock 配件端的端到端回环测试：
 * 内存双工流两端各跑一个链路通道，mock 按 DiPlay/xcertplay 的配件侧时序应答，
 * 验证 Phase 1 全序列（识别→MFi 跳过→HID→WiFi 配置→CarPlay 启动→通知）。
 */
class Iap2SenderSessionTest {
    private enum class BmwResponse {
        IMMEDIATE_WIFI,
        WIFI_AFTER_AVAILABILITY,
        START_AFTER_AVAILABILITY,
        EMPTY_START_THEN_WIFI,
        WIFI_DURING_HID,
        START_DURING_HID,
        CLOSE_AFTER_AVAILABILITY,
        MALFORMED_WIFI,
        BOTH_AFTER_AVAILABILITY,
    }

    @Test
    fun bmwLateWifiAfterAvailabilityImmediatelyNotifiesNetworkBinding() {
        val result = runBmwScenario(BmwResponse.WIFI_AFTER_AVAILABILITY)
        assertEquals("MockAP", result.endpoint!!.ssid)
        assertEquals(1, result.wifiCallbacks)
        assertTrue(result.events.any { it.contains("继续等待 0x5703/0x4301") })
    }

    @Test
    fun bmwLateStartSessionDespiteOmittedDeclarationProvidesEndpoint() {
        val result = runBmwScenario(BmwResponse.START_AFTER_AVAILABILITY)
        assertEquals("MockAP", result.endpoint!!.ssid)
        assertEquals(49152, result.endpoint.airPlayPort)
        assertEquals(0, result.wifiCallbacks)
    }

    @Test
    fun bmwWifiAndStartSessionInOneReadProduceCompleteInitialEndpoint() {
        val result = runBmwScenario(BmwResponse.BOTH_AFTER_AVAILABILITY)
        assertEquals("MockAP", result.endpoint!!.ssid)
        assertEquals(49152, result.endpoint.airPlayPort)
        assertEquals(1, result.wifiCallbacks)
    }

    @Test
    fun bmwStartWithoutApKeepsWaitingForWifiAndMergesBothMessages() {
        val result = runBmwScenario(BmwResponse.EMPTY_START_THEN_WIFI)
        assertEquals("MockAP", result.endpoint!!.ssid)
        assertEquals(49152, result.endpoint.airPlayPort)
        assertEquals(1, result.wifiCallbacks)
        assertTrue(result.events.any { it.contains("尚无热点 SSID") })
    }

    @Test
    fun bmwWifiDuringHidWaitIsNotLostAndCallbackRunsBeforeAvailability() {
        val result = runBmwScenario(BmwResponse.WIFI_DURING_HID)
        assertEquals("MockAP", result.endpoint!!.ssid)
        assertEquals(1, result.wifiCallbacks)
        assertTrue(result.events.indexOf("wifi-callback") < result.events.indexOfFirst { it.contains("tx=0x4300") })
    }

    @Test
    fun bmwStartDuringHidWaitRemainsPendingUntilItCanBeUsed() {
        val result = runBmwScenario(BmwResponse.START_DURING_HID)
        assertEquals("MockAP", result.endpoint!!.ssid)
        assertEquals(49152, result.endpoint.airPlayPort)
    }

    @Test
    fun bmwLateWaitIsCappedByOverallDeadline() {
        val result = runBmwScenario(BmwResponse.IMMEDIATE_WIFI, silent = true, timeoutMillis = 2_500)
        assertNull(result.endpoint)
        assertTrue(result.failure!!.message.orEmpty().contains("本次未取得车机热点凭据"))
        assertTrue(result.events.any { it.contains("继续等待 0x5703/0x4301") })
        assertTrue("The 8 s late window must not extend the overall deadline", result.elapsedMillis < 4_000)
    }

    @Test
    fun bmwLocallyClosedLinkEndsLateWaitPromptly() {
        val result = runBmwScenario(BmwResponse.CLOSE_AFTER_AVAILABILITY)
        assertNull(result.endpoint)
        assertTrue(result.failure!!.message.orEmpty().contains("蓝牙链路已关闭"))
        assertNotNull("The fixture must explicitly terminate the phone channel", result.millisAfterChannelClose)
        assertTrue("A terminated link must promptly wake the waiting session", result.millisAfterChannelClose!! < 1_000)
    }

    @Test
    fun bmwEmptyWifiDoesNotPublishCredentialsOrProduceEndpoint() {
        val result = runBmwScenario(BmwResponse.MALFORMED_WIFI)
        assertNull(result.endpoint)
        assertEquals(0, result.wifiCallbacks)
        assertTrue(result.failure!!.message.orEmpty().contains("未取得有效车机热点 SSID"))
    }

    private data class BmwResult(
        val endpoint: Iap2SenderSession.CarPlayEndpoint?,
        val failure: IOException?,
        val wifiCallbacks: Int,
        val elapsedMillis: Long,
        val millisAfterChannelClose: Long?,
        val events: List<String>,
    )

    private fun runBmwScenario(
        response: BmwResponse,
        silent: Boolean = false,
        timeoutMillis: Long = 10_000,
    ): BmwResult {
        val pair = InMemoryStreamPair()
        val phone = Iap2LinkChannel(pair.sideA, name = "bmw-v2-phone")
        val receiver = Iap2LinkChannel(pair.sideB, name = "bmw-v2-accessory")
        val closedAt = AtomicLong()
        val accessory = MockAccessory(
            receiver,
            bmwProbe = true,
            bmwSilent = silent,
            bmwDeclareHid = response == BmwResponse.WIFI_DURING_HID || response == BmwResponse.START_DURING_HID,
            bmwResponse = response,
            // 直接模拟本地取消/已确认断链；内存管道对端的 closePartner 只关闭输入端，
            // 其约 3s 的 Pipe broken 检测延迟不属于会话接收窗口。
            onBmwClose = {
                closedAt.set(System.nanoTime())
                phone.close()
            },
        )
        val mockFailure = AtomicReference<Throwable?>()
        val mockThread = Thread({ try { accessory.run() } catch (e: Throwable) { mockFailure.set(e) } }, "mock-bmw-v2").apply {
            isDaemon = true
            start()
        }
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        var callbackCount = 0
        var endpoint: Iap2SenderSession.CarPlayEndpoint? = null
        var failure: IOException? = null
        val started = System.nanoTime()
        try {
            try {
                endpoint = Iap2SenderSession(
                    phone,
                    log = { events.add(it) },
                    bmwProbeAttempts = AtomicInteger(),
                    onWifiCredentials = {
                        callbackCount++
                        events.add("wifi-callback")
                        assertEquals("MockAP", it.ssid)
                    },
                ).run(timeoutMillis)
            } catch (error: IOException) {
                failure = error
            }
            val finished = System.nanoTime()
            val elapsed = (finished - started) / 1_000_000
            val afterClose = closedAt.get().takeIf { it != 0L }?.let { (finished - it) / 1_000_000 }
            mockThread.join(1_000)
            assertTrue("Mock BMW must complete its expected sequence", !mockThread.isAlive)
            assertNull("Unexpected mock receiver failure", mockFailure.get())
            return BmwResult(endpoint, failure, callbackCount, elapsed, afterClose, events.toList())
        } finally {
            phone.close()
            receiver.close()
            mockThread.join(1_000)
        }
    }

    /** The observed BMW profile omits 0x5703 and 0x4301; a reply to a probe must be usable. */
    @Test
    fun bmwProbeAcceptsWifiResponseDespiteOmittedDeclaration() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "bmw-phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "bmw-accessory")
        val accessory = MockAccessory(accessoryChannel, bmwProbe = true, bmwDeclareHid = true)
        val accessoryThread = Thread({ accessory.run() }, "mock-bmw").apply { isDaemon = true; start() }
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            val endpoint = Iap2SenderSession(phoneChannel, log = { events.add(it) }, bmwProbeAttempts = AtomicInteger()).run(timeoutMillis = 10_000L)
            assertEquals("MockAP", endpoint.ssid)
            assertEquals("secret-passphrase", endpoint.passphrase)
            assertEquals(0, endpoint.airPlayPort)
            assertTrue(events.any { it.contains("EXPERIMENT: BMW") })
        } finally {
            phoneChannel.close()
            accessoryChannel.close()
            accessoryThread.join(2_000)
        }
    }

    /** A silent BMW-style receiver must not hold the session for the full overall deadline. */
    @Test
    fun bmwProbeStopsWaitingWhenReceiverIsSilent() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "bmw-phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "bmw-accessory")
        val accessory = MockAccessory(accessoryChannel, bmwProbe = true, bmwSilent = true)
        val accessoryThread = Thread({ accessory.run() }, "mock-bmw-silent").apply { isDaemon = true; start() }
        val start = System.nanoTime()
        try {
            try {
                Iap2SenderSession(phoneChannel, log = {}, bmwProbeAttempts = AtomicInteger()).run(timeoutMillis = 30_000L)
                throw AssertionError("A silent receiver must not produce an endpoint")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("本次未取得车机热点凭据"))
            }
            assertTrue("The silent receiver must get the complete 8 s late response window", System.nanoTime() - start >= 9_000_000_000L)
            assertTrue("Silent probe should finish well before the 30 s overall deadline", System.nanoTime() - start < 15_000_000_000L)
        } finally {
            phoneChannel.close()
            accessoryChannel.close()
            accessoryThread.join(2_000)
        }
    }

    @Test
    fun bmwProbeBudgetAllowsTwoSessionsThenPreventsAnotherRequest() {
        val budget = AtomicInteger()
        repeat(3) { index ->
            val exhausted = index == 2
            val pair = InMemoryStreamPair()
            val phoneChannel = Iap2LinkChannel(pair.sideA, name = "bmw-phone")
            val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "bmw-accessory")
            val accessory = MockAccessory(accessoryChannel, bmwProbe = true, bmwSilent = exhausted, bmwNoProbeExpected = exhausted)
            val mockFailure = AtomicReference<Throwable?>()
            val accessoryThread = Thread({ try { accessory.run() } catch (e: Throwable) { mockFailure.set(e) } }, "mock-bmw-budget").apply { isDaemon = true; start() }
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            try {
                val session = Iap2SenderSession(phoneChannel, log = { events.add(it) }, bmwProbeAttempts = budget)
                if (exhausted) {
                    try {
                        session.run(timeoutMillis = 10_000L)
                        throw AssertionError("An exhausted probe budget must not produce an endpoint")
                    } catch (expected: IOException) {
                        assertTrue(expected.message.orEmpty().contains("本次未取得车机热点凭据"))
                    }
                    assertTrue(events.any { it.contains("探测已达本次进程上限") })
                } else {
                    assertEquals("MockAP", session.run(timeoutMillis = 10_000L).ssid)
                    assertTrue(events.any { it.contains("EXPERIMENT: BMW 旧协议探测") })
                }
                accessoryThread.join(2_000)
                assertTrue("Mock BMW must complete its expected sequence", !accessoryThread.isAlive)
                assertNull("Unexpected mock receiver failure", mockFailure.get())
            } finally {
                phoneChannel.close()
                accessoryChannel.close()
            }
        }
    }

    @Test
    fun declaredNewBmwProtocolDoesNotConsumeProbeBudget() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "bmw-phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "bmw-accessory")
        val accessory = MockAccessory(accessoryChannel, bmwProbe = true, bmwNew = true, bmwNoProbeExpected = true)
        val mockFailure = AtomicReference<Throwable?>()
        val accessoryThread = Thread({ try { accessory.run() } catch (e: Throwable) { mockFailure.set(e) } }, "mock-bmw-new").apply { isDaemon = true; start() }
        val budget = AtomicInteger()
        try {
            val endpoint = Iap2SenderSession(phoneChannel, log = {}, bmwProbeAttempts = budget).run(timeoutMillis = 10_000L)
            assertEquals("MockAP", endpoint.ssid)
            assertEquals(49152, endpoint.airPlayPort)
            assertEquals(0, budget.get())
            accessoryThread.join(2_000)
            assertTrue("Mock receiver did not complete", !accessoryThread.isAlive)
            assertNull("New-protocol BMW should receive no legacy request", mockFailure.get())
        } finally {
            phoneChannel.close()
            accessoryChannel.close()
        }
    }

    @Test
    fun fullBringUpAgainstMockAccessory() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals("secret-passphrase", endpoint.passphrase)
        assertEquals(36, endpoint.channel)
        assertEquals(2, endpoint.securityType)
        assertEquals(49152, endpoint.airPlayPort)
        assertEquals("mock-device-id", endpoint.deviceIdentifier)
        assertNotNull(endpoint.publicKey)
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * 奥迪 MMI 9310 形态：识别帧只声明发 0x5703/0xea03（不发 0x4301），Phase 1
     * 因此不等待；车机在保活期间（Phase 1 之后）才补发 0x4301。
     * 旧实现把这条消息当 keep-alive 只记长度，配对永远不会重启——本用例锁住
     * 「晚到 0x4301 必须解析成端点并回调」这一行为。
     */
    @Test
    fun lateStartSessionAfterPhaseOneTriggersEndpointUpdate() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, audiDeclarations = true, lateStartSession = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val phaseOne = session.run(timeoutMillis = 10_000L)
        // 车机声明不发 0x4301：Phase 1 必须立即返回空端点，而不是白等 3s 窗口
        assertTrue(phaseOne.ipAddresses.isEmpty())

        val pump = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pump.isDaemon = true
        pump.start()

        assertTrue("晚到 0x4301 未触发端点回调", updateLatch.await(5, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals(49152, late.airPlayPort)
        assertEquals(listOf("192.168.49.1", "fd00::1"), late.ipAddresses)
        assertEquals("mock-device-id", late.deviceIdentifier)

        phoneChannel.close()
        accessoryChannel.close()
        pump.join(2_000)
    }

    /**
     * Audi MMI 3315 形态：0x4301 的地址字段按定长 NUL 填充（IPv6 后 22 个、IPv4 后 5 个）。
     * 旧实现只去掉**一个**尾部 NUL，得到 `"10.174.189.1\0\0\0\0\0"`——它 contains('.')=true、
     * contains(':')=false，能骗过所有「是不是 IPv4」的判断，但 Android 的 getaddrinfo 解不了，
     * 表现为 `Unable to resolve host "10.174.189.1"`（2026-10-18 实测：配对成功后视频端口
     * 与 keepAlive 全挂、会话 10 秒被拆）。
     */
    @Test
    fun nulPaddedAddressesAreStrippedCompletely() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, padAddresses = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        // 每个地址都必须干净：没有 NUL、没有空白
        endpoint.ipAddresses.forEach { address ->
            check(address.none { it == '\u0000' }) { "地址含 NUL：${address.toByteArray().toList()}" }
            check(address.trim() == address) { "地址含首尾空白：'$address'" }
        }
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)
        // 端口解析不受填充影响（同一帧里的 u32 参数）
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /** 按 DiPlay Iap2WirelessControlClient 的时序脚本化应答。 */
    private class MockAccessory(
        private val channel: Iap2LinkChannel,
        /** 奥迪形态：识别帧声明只发 0x5703/0xea03（不发 StartHID/0x4301）。 */
        private val audiDeclarations: Boolean = false,
        /** Phase 1 结束后再补发 0x4301（车机窗口晚开的真实时序）。 */
        private val lateStartSession: Boolean = false,
        /**
         * 地址字段按真实车机的定长填充（Audi MMI 3315 实测：IPv6 后跟 22 个 NUL、
         * IPv4 后跟 5 个）。用于锁定「必须去掉全部尾部 NUL」这一行为。
         */
        private val padAddresses: Boolean = false,
        private val bmwProbe: Boolean = false,
        private val bmwSilent: Boolean = false,
        private val bmwNoProbeExpected: Boolean = false,
        private val bmwNew: Boolean = false,
        private val bmwDeclareHid: Boolean = false,
        private val bmwResponse: BmwResponse = BmwResponse.IMMEDIATE_WIFI,
        private val onBmwClose: (() -> Unit)? = null,
    ) {
        private val parser = Iap2Csm.Parser()
        private val pending = ArrayDeque<Iap2Csm.Frame>()

        fun run() {
            check(channel.awaitReady(10_000)) { "mock link not ready" }

            expect(Iap2MessageIds.START_IDENTIFICATION)
            send(
                Iap2MessageIds.IDENTIFICATION_INFORMATION,
                Iap2Csm.string(0, "MockCarPlay"),
                Iap2Csm.string(1, if (bmwProbe) "MGU-50-P" else "MU-1"),
                Iap2Csm.string(2, if (bmwProbe) "BMW Group" else "Mock Manufacturer"),
                if (audiDeclarations) {
                    Iap2Csm.u16List(6, intArrayOf(0x5703, 0xea03))
                } else if (bmwProbe) {
                    val ids = mutableListOf(0xea03, 0x5000)
                    if (bmwNew) ids += 0x4301
                    if (bmwDeclareHid) ids += 0x6800
                    Iap2Csm.u16List(6, ids.toIntArray())
                } else {
                    ByteArray(0)
                },
                if (bmwProbe) Iap2Csm.u16List(7, intArrayOf(0x4e09, 0x4e0a)) else ByteArray(0),
            )
            expect(Iap2MessageIds.IDENTIFICATION_ACCEPTED)

            expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            send(Iap2MessageIds.MFI_CERTIFICATE, Iap2Csm.param(0, ByteArray(64) { it.toByte() }))
            expect(Iap2MessageIds.MFI_REQUEST_CHALLENGE)
            send(Iap2MessageIds.MFI_RESPONSE, Iap2Csm.param(0, ByteArray(70) { (it * 7).toByte() }))
            expect(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)

            if (bmwResponse == BmwResponse.WIFI_DURING_HID || bmwResponse == BmwResponse.START_DURING_HID) {
                if (bmwResponse == BmwResponse.WIFI_DURING_HID) sendWifi() else sendStartSession()
                send(Iap2MessageIds.START_HID, Iap2Csm.u16(0, 0))
                expect(Iap2MessageIds.DEVICE_HID_REPORT)
            }

            // 订阅 + StartHID（奥迪形态不发，发送端应直接跳过等待）
            if (!audiDeclarations && !bmwProbe) {
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                send(Iap2MessageIds.START_POWER_UPDATES)
                send(
                    Iap2MessageIds.START_HID,
                    Iap2Csm.u16(0, 0),
                    Iap2Csm.u16(1, 2),
                    Iap2Csm.u16(2, 1),
                    Iap2Csm.param(4, byteArrayOf(0x05, 0x0c)),
                )
                expect(Iap2MessageIds.DEVICE_HID_REPORT)
            }

            // WiFi 配置
            if (!bmwNoProbeExpected && bmwResponse != BmwResponse.WIFI_DURING_HID) {
                expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
            }
            // 迟到一条订阅请求（真实车机 bring-up 常见：0x5000 与 0x5703 相邻到达）。
            // 它必须被暂存而不是在 expectOrNull 里被反复重读——旧实现把非预期帧塞回
            // 「自己下一轮优先读取」的队列，同一帧无限循环（2026-10-18 日志刷屏根因）。
            if (!bmwSilent && !bmwNoProbeExpected && bmwResponse == BmwResponse.IMMEDIATE_WIFI) {
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                sendWifi()
            }

            // 设备状态问候（真机顺序：0x4E0D/0x4E0A/0x4E09/0x4E0B/0x4E0C → 0x4300）
            expect(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE)
            expect(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE)
            expect(Iap2MessageIds.DEVICE_INFORMATION_UPDATE)
            expect(Iap2MessageIds.DEVICE_TIME_UPDATE)
            expect(Iap2MessageIds.DEVICE_UUID_UPDATE)

            // CarPlay 启动
            expect(Iap2MessageIds.CARPLAY_AVAILABILITY)
            when (bmwResponse) {
                BmwResponse.WIFI_AFTER_AVAILABILITY -> {
                    send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                    Thread.sleep(100)
                    sendWifi()
                }
                BmwResponse.START_AFTER_AVAILABILITY -> {
                    Thread.sleep(100)
                    sendStartSession()
                }
                BmwResponse.EMPTY_START_THEN_WIFI -> {
                    send(Iap2MessageIds.CARPLAY_START_SESSION, Iap2Csm.u32(2, 49152L))
                    Thread.sleep(100)
                    sendWifi()
                }
                BmwResponse.CLOSE_AFTER_AVAILABILITY -> {
                    onBmwClose?.invoke() ?: channel.close()
                    return
                }
                BmwResponse.MALFORMED_WIFI -> {
                    send(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION, Iap2Csm.string(1, ""))
                    return
                }
                BmwResponse.BOTH_AFTER_AVAILABILITY -> {
                    sendEncoded(
                        Iap2Csm.encode(
                            Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                            Iap2Csm.string(1, "MockAP") + Iap2Csm.string(2, "secret-passphrase"),
                        ) + Iap2Csm.encode(Iap2MessageIds.CARPLAY_START_SESSION, Iap2Csm.u32(2, 49152L)),
                    )
                }
                else -> Unit
            }
            if (!lateStartSession && (!bmwProbe || bmwNew)) {
                sendStartSession()
            }

            if (bmwSilent) return
            expect(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
            if (lateStartSession) {
                // 车机打开 CarPlay 窗口后补发（Phase 1 早已结束）
                Thread.sleep(150)
                sendStartSession()
            }
        }

        private fun sendWifi() {
            send(
                Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                Iap2Csm.string(1, "MockAP"),
                Iap2Csm.string(2, "secret-passphrase"),
                Iap2Csm.u8(3, 2),
                Iap2Csm.u8(4, 36),
            )
        }

        private fun sendStartSession() {
            send(
                Iap2MessageIds.CARPLAY_START_SESSION,
                Iap2Csm.group(
                    1,
                    Iap2Csm.string(0, "MockAP"),
                    Iap2Csm.string(1, "secret-passphrase"),
                    Iap2Csm.u8(2, 36),
                    addressParam(3, "192.168.49.1", padTo = 17),
                    addressParam(3, "fd00::1", padTo = 47),
                    Iap2Csm.u8(4, 2),
                ),
                Iap2Csm.u32(2, 49152L),
                Iap2Csm.string(3, "mock-device-id"),
                Iap2Csm.string(4, "a".repeat(64) + "publickeyhex"),
                Iap2Csm.string(5, "560.20"),
            )
        }

        /** 地址参数：真实车机按定长 NUL 填充；默认（不填充）与之前行为一致。 */
        private fun addressParam(
            id: Int,
            value: String,
            padTo: Int,
        ): ByteArray {
            val raw = value.encodeToByteArray()
            if (!padAddresses) return Iap2Csm.param(id, raw + byteArrayOf(0))
            // 至少保留一个 NUL 结尾（CSM 字符串语义），其余补到固定宽度
            val width = maxOf(padTo, raw.size + 1)
            val padded = raw + ByteArray(width - raw.size)
            return Iap2Csm.param(id, padded)
        }

        private fun send(
            messageId: Int,
            vararg body: ByteArray,
        ) {
            val joined =
                when (body.size) {
                    0 -> ByteArray(0)
                    1 -> body[0]
                    else -> body.reduce { acc, bytes -> acc + bytes }
                }
            val frame = Iap2Csm.encode(messageId, joined)
            sendEncoded(frame)
        }

        private fun sendEncoded(frame: ByteArray) {
            var offset = 0
            val limit = channel.peerMaxControlPayload() ?: 2048
            while (offset < frame.size) {
                val end = minOf(offset + limit, frame.size)
                check(channel.sendControl(frame.copyOfRange(offset, end))) { "mock send failed" }
                offset = end
            }
        }

        private fun expect(messageId: Int) {
            println("MOCK expect 0x${messageId.toString(16)}")
            val frame =
                recvFrame(10_000)
                    ?: error("mock timed out waiting 0x${messageId.toString(16)}")
            check(frame.messageId == messageId) {
                "mock expected 0x${messageId.toString(16)} got 0x${frame.messageId.toString(16)}"
            }
            println("MOCK got 0x${frame.messageId.toString(16)}")
        }

        private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
            pending.firstOrNull()?.let {
                pending.removeFirst()
                return it
            }
            val bytes = channel.recvControl(timeoutMillis) ?: return null
            val frames = parser.offer(bytes)
            if (frames.isEmpty()) return null
            frames.forEachIndexed { index, frame -> if (index > 0) pending += frame }
            return frames.first()
        }
    }
}
