package io.github.shaonialife.cardeck.input

/** One manually armed, non-routing sample. Discard arbitrary URLs, numbers and contacts. */
internal class SteeringDialProbe {
    enum class Event(val label: String) {
        PHONE("电话页面请求"), VOICE("语音请求"), NATIVE("原车切屏"), OTHER_UI("其它页面请求"),
        TOUCH("车机触屏输入"), ROTARY("iDrive 旋钮输入"), UNKNOWN_HID("来源未识别的输入报告"),
    }
    class Window(val generation: Long, val deadline: Long) { internal val events = linkedSetOf<Event>() }
    private var window: Window? = null

    @Synchronized fun start(generation: Long, now: Long) = Window(generation, now + 8_000).also { window = it }

    @Synchronized fun record(
        generation: Long,
        now: Long,
        command: Map<*, *>,
        hidDescriptors: Map<String, ByteArray> = emptyMap(),
        rotaryIds: Set<String> = emptySet(),
    ): Boolean {
        val active = window ?: return false
        if (generation != active.generation || now >= active.deadline) return false
        val params = command["params"] as? Map<*, *>
        val event = when (command["type"]) {
            "requestSiri" -> Event.VOICE
            "requestUI" -> if ((params?.get("url") as? String).equals("mobilephone:", true)) Event.PHONE else Event.OTHER_UI
            "changeModes" -> {
                val rows = params?.get("resources") as? List<*> ?: return false
                if (rows.none { ((it as? Map<*, *>)?.get("resourceID") as? Number)?.toLong() == 1L }) return false
                Event.NATIVE
            }
            "hidSendReport" -> {
                val report = command["hidReport"] as? ByteArray ?: return false
                if (report.isEmpty()) return false
                val id = command["uuid"]?.toString()
                val descriptor = id?.let(hidDescriptors::get)
                when {
                    descriptor == null -> Event.UNKNOWN_HID
                    id in rotaryIds -> Event.ROTARY
                    // Recognize only the connected device's declared touch-screen collection.
                    // Input lengths and numeric UUIDs are shared by unrelated devices.
                    descriptor.size >= touchScreenCollection.size &&
                        touchScreenCollection.indices.all { descriptor[it] == touchScreenCollection[it] } &&
                        TouchReports.formatFromDescriptor(descriptor) != null -> Event.TOUCH
                    else -> Event.UNKNOWN_HID
                }
            }
            else -> return false
        }
        active.events.add(event)
        return event == Event.VOICE
    }

    @Synchronized fun finish(sample: Window): List<Event>? {
        if (window !== sample) return null
        window = null
        return sample.events.toList()
    }

    private companion object {
        // Usage Page (Digitizers), Usage (Touch Screen), Collection (Application).
        val touchScreenCollection = byteArrayOf(0x05, 0x0D, 0x09, 0x04, 0xA1.toByte(), 0x01)
    }
}
