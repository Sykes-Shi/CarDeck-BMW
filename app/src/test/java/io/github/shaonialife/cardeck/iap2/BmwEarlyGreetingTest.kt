package io.github.shaonialife.cardeck.iap2

import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.InMemoryStreamPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A receiver that requires the ready notification before enabling HID and Wi-Fi. */
class BmwEarlyGreetingTest {
    @Test
    fun carPlayProfileReceivesReadyBeforeHidAndWifiRequest() {
        runScenario(earlyGreeting = true)
    }

    @Test
    fun carPlayWifiArrivingDuringHidIsDeliveredWithoutAnotherRequest() {
        runScenario(earlyGreeting = true, earlyWifi = true)
    }

    @Test
    fun otherBmwEaProtocolRetainsOriginalOrdering() {
        runScenario(earlyGreeting = false, eaName = "de.bmw.a4a.bt")
    }

    @Test
    fun otherModelRetainsOriginalOrdering() {
        runScenario(earlyGreeting = false, model = "MGU-118")
    }

    @Test
    fun similarModelDoesNotEnableTheInviteWait() {
        runScenario(earlyGreeting = true, model = "MGU-50-Plus", preferCtrlInvitation = false)
    }

    @Test
    fun otherManufacturerRetainsOriginalOrdering() {
        runScenario(earlyGreeting = false, manufacturer = "Mock Manufacturer")
    }

    @Test
    fun missingReceiveWifiDeclarationRetainsOriginalOrdering() {
        runScenario(earlyGreeting = false, receivesWifi = false)
    }

    @Test
    fun missingSendWifiDeclarationRetainsOriginalOrdering() {
        runScenario(earlyGreeting = false, sendsWifi = false)
    }

    private fun runScenario(
        earlyGreeting: Boolean,
        preferCtrlInvitation: Boolean = earlyGreeting,
        earlyWifi: Boolean = false,
        model: String = "MGU-50-P",
        manufacturer: String = "BMW Group",
        eaName: String = "com.bmw.carplay",
        receivesWifi: Boolean = true,
        sendsWifi: Boolean = true,
    ) {
        val pair = InMemoryStreamPair()
        val phone = Iap2LinkChannel(pair.sideA, name = "bmw-v3-phone")
        val receiver = Iap2LinkChannel(pair.sideB, name = "bmw-v3-receiver")
        val wire = ScriptedReceiver(receiver)
        val receiverFailure = AtomicReference<Throwable?>()
        val budget = AtomicInteger()
        val callbacks = AtomicInteger()
        val legacyWifi = receivesWifi && sendsWifi
        val events = mutableListOf<String>()
        val receiverThread = Thread({
            try {
                check(receiver.awaitReady(5_000))
                wire.expect(Iap2MessageIds.START_IDENTIFICATION)
                val sendIds = mutableListOf(Iap2MessageIds.START_HID)
                if (sendsWifi) sendIds += Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION
                if (!legacyWifi) sendIds += Iap2MessageIds.CARPLAY_START_SESSION
                val receiveIds = mutableListOf(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE)
                if (receivesWifi) receiveIds += Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION
                wire.send(
                    Iap2MessageIds.IDENTIFICATION_INFORMATION,
                    Iap2Csm.string(0, "MockReceiver"),
                    Iap2Csm.string(1, model),
                    Iap2Csm.string(2, manufacturer),
                    Iap2Csm.u16List(6, sendIds.toIntArray()),
                    Iap2Csm.u16List(7, receiveIds.toIntArray()),
                    Iap2Csm.group(10, Iap2Csm.u8(0, 8), Iap2Csm.string(1, eaName)),
                )
                wire.expect(Iap2MessageIds.IDENTIFICATION_ACCEPTED)
                wire.expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
                wire.send(Iap2MessageIds.MFI_CERTIFICATE, Iap2Csm.param(0, ByteArray(64)))
                wire.expect(Iap2MessageIds.MFI_REQUEST_CHALLENGE)
                wire.send(Iap2MessageIds.MFI_RESPONSE, Iap2Csm.param(0, ByteArray(70)))
                wire.expect(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
                wire.expect(Iap2MessageIds.EA_OPEN_SESSION)

                // This receiver will not enable HID or send Wi-Fi until it has the whole greeting.
                if (earlyGreeting) wire.expectGreeting()
                if (earlyWifi) wire.sendWifi()
                wire.send(Iap2MessageIds.START_HID, Iap2Csm.u16(0, 0))
                wire.expect(Iap2MessageIds.DEVICE_HID_REPORT)
                if (legacyWifi && !earlyWifi) {
                    wire.expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                    wire.sendWifi()
                }
                if (!earlyGreeting) wire.expectGreeting()
                wire.expect(Iap2MessageIds.CARPLAY_AVAILABILITY)
                if (!legacyWifi) {
                    wire.send(
                        Iap2MessageIds.CARPLAY_START_SESSION,
                        Iap2Csm.group(1, Iap2Csm.string(0, "MockAP"), Iap2Csm.string(1, "mock-passphrase")),
                        Iap2Csm.u32(2, 49152),
                    )
                }
                wire.expect(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
            } catch (error: Throwable) {
                receiverFailure.set(error)
                phone.close()
            }
        }, "mock-bmw-v3").apply { isDaemon = true; start() }
        try {
            val endpoint = Iap2SenderSession(
                phone,
                log = { events.add(it) },
                onWifiCredentials = {
                    assertEquals("MockAP", it.ssid)
                    assertEquals("mock-passphrase", it.passphrase)
                    callbacks.incrementAndGet()
                },
                bmwProbeAttempts = budget,
            ).run(5_000)
            receiverThread.join(1_000)
            assertTrue("The mock receiver must complete the expected sequence", !receiverThread.isAlive)
            assertNull("Unexpected receiver sequence", receiverFailure.get())
            assertEquals("MockAP", endpoint.ssid)
            assertEquals("mock-passphrase", endpoint.passphrase)
            assertEquals(if (legacyWifi) 0 else 49152, endpoint.airPlayPort)
            assertEquals(preferCtrlInvitation, endpoint.preferCtrlInvitation)
            assertEquals(if (legacyWifi) 1 else 0, callbacks.get())
            assertEquals("Declared Wi-Fi support must not consume the v2 probe budget", 0, budget.get())
            assertEquals(earlyGreeting, events.any { it.startsWith("EXPERIMENT v3:") })
            GREETING_IDS.forEach { id ->
                assertEquals("Greeting 0x${id.toString(16)} must be sent exactly once", 1, wire.receivedIds.count { it == id })
            }
            assertEquals(
                if (legacyWifi && !earlyWifi) 1 else 0,
                wire.receivedIds.count { it == Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION },
            )
            assertEquals(1, wire.receivedIds.count { it == Iap2MessageIds.CARPLAY_AVAILABILITY })
        } finally {
            phone.close()
            receiver.close()
            receiverThread.join(1_000)
        }
    }

    private class ScriptedReceiver(private val channel: Iap2LinkChannel) {
        private val parser = Iap2Csm.Parser()
        private val pending = ArrayDeque<Iap2Csm.Frame>()
        val receivedIds = mutableListOf<Int>()

        fun expectGreeting() {
            GREETING_IDS.forEach { expect(it) }
        }

        fun expect(messageId: Int) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (pending.isEmpty()) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                check(remaining > 0) { "Timed out waiting for 0x${messageId.toString(16)}" }
                val bytes = channel.recvControl(remaining) ?: error("No frame for 0x${messageId.toString(16)}")
                pending.addAll(parser.offer(bytes))
            }
            val frame = pending.removeFirst()
            receivedIds += frame.messageId
            check(frame.messageId == messageId) {
                "Expected 0x${messageId.toString(16)}, got 0x${frame.messageId.toString(16)}"
            }
        }

        fun sendWifi() {
            send(
                Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                Iap2Csm.string(1, "MockAP"),
                Iap2Csm.string(2, "mock-passphrase"),
                Iap2Csm.u8(3, 2),
                Iap2Csm.u8(4, 36),
            )
        }

        fun send(messageId: Int, vararg body: ByteArray) {
            val bytes = Iap2Csm.encode(messageId, body.fold(ByteArray(0)) { all, next -> all + next })
            check(channel.sendControl(bytes)) { "Mock channel closed" }
        }
    }

    private companion object {
        val GREETING_IDS = listOf(
            Iap2MessageIds.WIRELESS_CARPLAY_UPDATE,
            Iap2MessageIds.DEVICE_LANGUAGE_UPDATE,
            Iap2MessageIds.DEVICE_INFORMATION_UPDATE,
            Iap2MessageIds.DEVICE_TIME_UPDATE,
            Iap2MessageIds.DEVICE_UUID_UPDATE,
        )
    }
}
