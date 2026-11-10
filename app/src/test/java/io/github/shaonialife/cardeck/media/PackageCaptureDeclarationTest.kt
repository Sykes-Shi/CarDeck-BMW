package io.github.shaonialife.cardeck.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PackageCaptureDeclarationTest {
    private fun read(vararg lines: String): String =
        PackageCaptureDeclaration.readDump { emit -> lines.forEach(emit) }

    @Test
    fun readsAllowedDeclaration() {
        assertEquals("allowed", read("allowsPlaybackCapture=true"))
    }

    @Test
    fun readsDisallowedDeclaration() {
        assertEquals("disallowed", read("allowsPlaybackCapture=false"))
    }

    @Test
    fun trimsOnlySurroundingWhitespace() {
        assertEquals("allowed", read(" \tallowsPlaybackCapture=true\t "))
        assertEquals("disallowed", read("  allowsPlaybackCapture=false  "))
    }

    @Test
    fun missingDeclarationIsUnknown() {
        assertEquals("unknown", read())
        assertEquals("unknown", read("", "targetSdkVersion=34"))
    }

    @Test
    fun changedFieldFormatIsUnknownEvenAlongsideValidLine() {
        val malformed = listOf(
            "allowsPlaybackCapture",
            "allowsPlaybackCapture=",
            "allowsPlaybackCapture=TRUE",
            "allowsPlaybackCapture=1",
            "allowsPlaybackCapture =true",
            "allowsPlaybackCapture= true",
            "allowsPlaybackCapture:true",
            "allowsPlaybackCapture(true)",
            "allowsPlaybackCapture=true extra",
        )
        for (line in malformed) {
            assertEquals(line, "unknown", read(line))
            assertEquals(line, "unknown", read("allowsPlaybackCapture=true", line))
            assertEquals(line, "unknown", read(line, "allowsPlaybackCapture=false"))
        }
    }

    @Test
    fun conflictingDeclarationsAreUnknownInEitherOrder() {
        assertEquals("unknown", read("allowsPlaybackCapture=true", "allowsPlaybackCapture=false"))
        assertEquals("unknown", read("allowsPlaybackCapture=false", "allowsPlaybackCapture=true"))
        assertEquals(
            "unknown",
            read("allowsPlaybackCapture=true", "allowsPlaybackCapture=false", "allowsPlaybackCapture=true"),
        )
    }

    @Test
    fun repeatedSameDeclarationIsConsistent() {
        assertEquals("allowed", read("allowsPlaybackCapture=true", "allowsPlaybackCapture=true"))
        assertEquals("disallowed", read("allowsPlaybackCapture=false", "allowsPlaybackCapture=false"))
    }

    @Test
    fun dumpFailureNeverReturnsPartialDeclaration() {
        assertEquals("unknown", PackageCaptureDeclaration.readDump { throw IllegalStateException() })
        for (value in listOf("true", "false")) {
            assertEquals(
                "unknown",
                PackageCaptureDeclaration.readDump { emit ->
                    emit("allowsPlaybackCapture=$value")
                    throw IllegalStateException()
                },
            )
        }
    }

    @Test
    fun unrelatedLinesDoNotMatchOrOverrideDeclaration() {
        val unrelated = arrayOf(
            "sourceDir=/data/app/allowsPlaybackCapture=true/base.apk",
            "anotherField=allowsPlaybackCapture=false",
            "notallowsPlaybackCapture=false",
            "allowsPlaybackCaptureOther=false",
            "allowsPlaybackCapture_enabled=false",
        )
        assertEquals("unknown", read(*unrelated))
        assertEquals("allowed", read("allowsPlaybackCapture=true", *unrelated))
        assertEquals("disallowed", read(*unrelated, "allowsPlaybackCapture=false"))
    }
}
