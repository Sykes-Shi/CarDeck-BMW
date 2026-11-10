package io.github.shaonialife.cardeck.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模拟明确声明 ZeroACK 的车机，验证只改变普通数据的裸 ACK，保留协商与序号。 */
class Iap2ZeroAckTest {
    @Test
    fun strictZeroAckPeerReceivesControlRepliesWithUnchangedSequenceAndPiggybackAck() {
        val phone = Iap2LinkEngine(suppressNormalAcknowledgementsForZeroAck = true)
        negotiate(phone)
        assertTrue(phone.zeroAckSuppressionActive)

        val startIdentification = Iap2Csm.encode(0x1D00)
        assertTrue(phone.sendControl(startIdentification, 3))
        val initial = frames(phone.takeOutput()).single()
        assertEquals(101, initial.sequence)
        assertArrayEquals(startIdentification, initial.payload)

        val identification = Iap2Csm.encode(0x1D01, Iap2Csm.string(0, "Synthetic receiver"))
        phone.feed(packet(0x40, 239, 101, 1, identification), 4)
        assertArrayEquals(identification, pollControl(phone))
        assertTrue("严格零 ACK 接收端此处不能收到单独的确认包", phone.takeOutput().isEmpty())

        val accepted = Iap2Csm.encode(0x1D02)
        val certificateRequest = Iap2Csm.encode(0xAA00)
        assertTrue(phone.sendControl(accepted, 5))
        assertTrue(phone.sendControl(certificateRequest, 5))
        val replies = frames(phone.takeOutput())
        assertEquals(2, replies.size)
        assertEquals(listOf(102, 103), replies.map { it.sequence })
        replies.forEach {
            assertEquals("数据仍须捎带 ACK 位", 0x40, it.control)
            assertEquals(239, it.ack)
            assertEquals(1, it.session)
            assertTrue("严格接收端只接受带载荷的后续数据", it.payload.isNotEmpty())
        }
        assertArrayEquals(accepted, replies[0].payload)
        assertArrayEquals(certificateRequest, replies[1].payload)

        val next = Iap2Csm.encode(0xAA06)
        phone.feed(packet(0x40, 240, 103, 1, next), 6)
        assertArrayEquals(next, pollControl(phone))
        assertTrue(phone.takeOutput().isEmpty())
        phone.advanceTime(60_000)
        assertTrue("已协商零重传的链路无需 ACK 来冲刷窗口", phone.takeOutput().isEmpty())
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
    }

    @Test
    fun anyNonZeroAcknowledgementOrRetransmissionFieldKeepsNormalAck() {
        val zero = zeroProfile()
        val profiles =
            listOf(
                zero.copy(maxAcknowledgements = 1),
                zero.copy(maxRetransmissions = 1),
                zero.copy(acknowledgementTimeoutMillis = 1),
                zero.copy(retransmissionTimeoutMillis = 1),
            )
        profiles.forEach { profile ->
            val phone = Iap2LinkEngine(suppressNormalAcknowledgementsForZeroAck = true)
            negotiate(phone, profile)
            assertFalse("任一字段非零都应保留旧行为: $profile", phone.zeroAckSuppressionActive)
            receiveAndAssertNormalAck(phone)
        }
    }

    @Test
    fun defaultEngineRetainsNormalAckEvenForExplicitZeroProfile() {
        val phone = Iap2LinkEngine()
        negotiate(phone)
        assertFalse(phone.zeroAckSuppressionActive)
        receiveAndAssertNormalAck(phone)
    }

    @Test
    fun tcpEchoFirstModeRetainsNormalAckEvenWithExperimentEnabled() {
        val phone = Iap2LinkEngine(suppressNormalAcknowledgementsForZeroAck = true)
        phone.echoFirst = true
        negotiate(phone)
        assertFalse(phone.zeroAckSuppressionActive)
        receiveAndAssertNormalAck(phone)
    }

    @Test
    fun zeroProfileStillReceivesSynAckAndNegotiationRetryAck() {
        val phone = Iap2LinkEngine(suppressNormalAcknowledgementsForZeroAck = true)
        var reportedOwnSync: Iap2LinkEngine.SynchronizationPayload? = null
        phone.onSynchronizationEchoParameters = { reportedOwnSync = it }
        phone.start(0)
        phone.takeOutput()
        phone.feed(packet(0x80, 238, 0, 0, zeroProfile().encode()), 1)
        val synAck = frames(phone.takeOutput()).single()
        assertEquals(0xC0, synAck.control)
        assertEquals(100, synAck.sequence)
        assertEquals(238, synAck.ack)
        assertEquals(Iap2LinkEngine.SynchronizationPayload.decode(synAck.payload), reportedOwnSync)
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
        assertFalse(phone.zeroAckSuppressionActive)

        phone.advanceTime(500)
        val confirmation = frames(phone.takeOutput()).single()
        assertEquals(0x40, confirmation.control)
        assertEquals(0, confirmation.session)
        assertEquals(238, confirmation.ack)
        assertTrue("握手补确认保持原行为", confirmation.payload.isEmpty())
        phone.feed(packet(0x40, 238, 100, 0), 501)
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        assertTrue(phone.zeroAckSuppressionActive)
    }

    @Test
    fun duplicateDataStillGetsLegacyAckWithoutSecondDelivery() {
        val phone = Iap2LinkEngine(suppressNormalAcknowledgementsForZeroAck = true)
        negotiate(phone)
        val payload = Iap2Csm.encode(0x1D01)
        val data = packet(0x40, 239, 100, 1, payload)
        phone.feed(data, 3)
        assertArrayEquals(payload, pollControl(phone))
        assertTrue(phone.takeOutput().isEmpty())

        phone.feed(data, 4)
        assertEquals(null, phone.pollEvent())
        val ack = frames(phone.takeOutput()).single()
        assertEquals(239, ack.ack)
        assertTrue("重复包恢复机制不在本轮实验范围内", ack.payload.isEmpty())
    }

    @Test
    fun suppressionIsInactiveWithoutPeerOrAfterTransportOrStateChange() {
        val phone = Iap2LinkEngine(suppressNormalAcknowledgementsForZeroAck = true)
        assertFalse(phone.zeroAckSuppressionActive)
        phone.enterNegotiating()
        assertFalse(phone.zeroAckSuppressionActive)
        phone.initiate(0)
        phone.takeOutput()
        phone.feed(packet(0x80, 238, 0, 0, zeroProfile().encode()), 1)
        phone.takeOutput()
        assertFalse(phone.zeroAckSuppressionActive)
        phone.feed(packet(0x40, 238, 100, 0), 2)
        assertTrue(phone.zeroAckSuppressionActive)
        phone.echoFirst = true
        assertFalse(phone.zeroAckSuppressionActive)
        phone.echoFirst = false
        assertTrue(phone.zeroAckSuppressionActive)
        phone.feedEof()
        assertFalse(phone.zeroAckSuppressionActive)
    }

    private fun negotiate(
        phone: Iap2LinkEngine,
        profile: Iap2LinkEngine.SynchronizationPayload = zeroProfile(),
    ) {
        phone.start(0)
        phone.takeOutput()
        phone.feed(packet(0x80, 238, 0, 0, profile.encode()), 1)
        val synAck = frames(phone.takeOutput()).single()
        assertEquals("零 ACK 选项不能抑制 SYN|ACK", 0xC0, synAck.control)
        phone.feed(packet(0x40, 238, synAck.sequence, 0), 2)
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        assertTrue(phone.takeOutput().isEmpty())
    }

    private fun receiveAndAssertNormalAck(phone: Iap2LinkEngine) {
        val payload = Iap2Csm.encode(0x1D01)
        phone.feed(packet(0x40, 239, 100, 1, payload), 3)
        assertArrayEquals(payload, pollControl(phone))
        val ack = frames(phone.takeOutput()).single()
        assertEquals(0x40, ack.control)
        assertEquals(100, ack.sequence)
        assertEquals(239, ack.ack)
        assertEquals(0, ack.session)
        assertTrue(ack.payload.isEmpty())
    }

    private fun pollControl(phone: Iap2LinkEngine): ByteArray {
        val event = phone.pollEvent()
        assertTrue(event is Iap2LinkEngine.Event.Control)
        return (event as Iap2LinkEngine.Event.Control).bytes
    }

    private fun zeroProfile() =
        Iap2LinkEngine.SynchronizationPayload(
            maxOutgoing = 5,
            maxLength = 2048,
            retransmissionTimeoutMillis = 0,
            acknowledgementTimeoutMillis = 0,
            maxRetransmissions = 0,
            maxAcknowledgements = 0,
            sessions = listOf(Iap2LinkEngine.SessionDescriptor(1, 0, 2)),
        )

    private data class Frame(
        val control: Int,
        val sequence: Int,
        val ack: Int,
        val session: Int,
        val payload: ByteArray,
    )

    /** 合成车机帧，避免被测引擎与模拟接收端共享发送实现。 */
    private fun packet(
        control: Int,
        sequence: Int,
        ack: Int,
        session: Int,
        payload: ByteArray? = null,
    ): ByteArray {
        val bytes = ByteArray(9 + (payload?.let { it.size + 1 } ?: 0))
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0x5A
        bytes[2] = (bytes.size ushr 8).toByte()
        bytes[3] = bytes.size.toByte()
        bytes[4] = control.toByte()
        bytes[5] = sequence.toByte()
        bytes[6] = ack.toByte()
        bytes[7] = session.toByte()
        bytes[8] = (-bytes.take(8).sumOf { it.toInt() and 0xFF }).toByte()
        if (payload != null) {
            payload.copyInto(bytes, 9)
            bytes[bytes.lastIndex] = (-payload.sumOf { it.toInt() and 0xFF }).toByte()
        }
        return bytes
    }

    private fun frames(bytes: ByteArray): List<Frame> {
        val frames = mutableListOf<Frame>()
        var offset = 0
        while (offset < bytes.size) {
            assertEquals(0xFF, bytes[offset].toInt() and 0xFF)
            assertEquals(0x5A, bytes[offset + 1].toInt() and 0xFF)
            val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
            assertEquals(0, bytes.copyOfRange(offset, offset + 9).sumOf { it.toInt() and 0xFF } and 0xFF)
            val payload = if (length > 9) bytes.copyOfRange(offset + 9, offset + length - 1) else byteArrayOf()
            if (length > 9) {
                assertEquals(0, bytes.copyOfRange(offset + 9, offset + length).sumOf { it.toInt() and 0xFF } and 0xFF)
            }
            frames +=
                Frame(
                    bytes[offset + 4].toInt() and 0xFF,
                    bytes[offset + 5].toInt() and 0xFF,
                    bytes[offset + 6].toInt() and 0xFF,
                    bytes[offset + 7].toInt() and 0xFF,
                    payload,
                )
            offset += length
        }
        return frames
    }
}
