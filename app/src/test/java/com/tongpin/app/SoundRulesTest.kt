package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class SoundRulesTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid sound to be rejected") }
        catch (expected: IllegalArgumentException) { assertFalse(expected.message.isNullOrBlank()) }
    }

    @Test fun fileSizeAcceptsTheBoundaryAndRejectsEmptyOrOversizedFiles() {
        SoundRules.validateSize(1)
        SoundRules.validateSize(20L * 1024 * 1024)
        listOf(-1L, 0L, SoundRules.MAX_IMPORT_BYTES + 1L, Long.MAX_VALUE).forEach { size ->
            rejects { SoundRules.validateSize(size) }
        }
    }

    @Test fun durationAcceptsUpToSixtySecondsWithoutRoundingAwayTheLimit() {
        SoundRules.validateDuration(1)
        SoundRules.validateDuration(59_999)
        SoundRules.validateDuration(60_000)
        listOf(-1L, 0L, 60_001L, Long.MAX_VALUE).forEach { duration ->
            rejects { SoundRules.validateDuration(duration) }
        }
    }

    @Test fun tenCustomSoundsStillAllowDeduplicationButNotANewImport() {
        SoundRules.validateCapacity(0)
        SoundRules.validateCapacity(9)
        SoundRules.validateCapacity(10, duplicate = true)
        rejects { SoundRules.validateCapacity(10) }
        rejects { SoundRules.validateCapacity(11) }
        rejects { SoundRules.validateCapacity(-1, duplicate = true) }
    }

    @Test fun identifierUsesTheFullContentDigestAndHasStableSignedByteFormatting() {
        val digest = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        val id = SoundRules.customId(digest)
        assertEquals("custom_ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", id)
        assertTrue(SoundRules.isCustomId(id))
        assertNotEquals(id, SoundRules.customId(MessageDigest.getInstance("SHA-256").digest("abcd".toByteArray())))
        rejects { SoundRules.customId(ByteArray(31)) }
        rejects { SoundRules.customId(ByteArray(33)) }
    }

    @Test fun metadataIdentifiersRejectPathsTruncationAndBuiltInNames() {
        listOf("chime", "../custom_" + "a".repeat(64), "custom_" + "a".repeat(63), "custom_" + "A".repeat(64),
            "custom_" + "a".repeat(64) + ".wav", "custom_" + "g".repeat(64)).forEach { id ->
            assertFalse(id, SoundRules.isCustomId(id))
        }
    }

    @Test fun importedNamesDiscardPathsKnownExtensionsControlsAndBidiFormatting() {
        assertEquals("晨间铃声", SoundRules.displayName("/storage/music/晨间铃声.WAV"))
        assertEquals("铃 声", SoundRules.displayName("C:\\Downloads\\铃\n\t声.mp3"))
        assertEquals("song", SoundRules.displayName("\u202Esong\u0000.m4a"))
        assertEquals("reading.v2", SoundRules.displayName("reading.v2.flac"))
        assertEquals("é", SoundRules.displayName("e\u0301.ogg"))
    }

    @Test fun namesAreLimitedByCodePointsWithoutSplittingEmoji() {
        val name = "🎵".repeat(41)
        val result = SoundRules.displayName("$name.mp3")
        assertEquals("🎵".repeat(40), result)
        assertEquals(40, result.codePointCount(0, result.length))
        assertEquals("中".repeat(40), SoundRules.displayName("中".repeat(60) + ".wav"))
    }

    @Test fun missingAndEmptyNamesHaveAReadableFallbackAndStoredNamesAreIdempotent() {
        listOf(null, "", "  ", "\u0000\u202E", ".", "..").forEach { raw ->
            assertEquals("自定义提示音", SoundRules.displayName(raw))
        }
        assertEquals("song.mp3", SoundRules.displayName("song.mp3.mp3"))
        assertEquals("song.mp3", SoundRules.sanitizeName("song.mp3"))
        assertEquals("Song Number 2", SoundRules.sanitizeName("  Song   Number\t2  "))
    }

    @Test fun storageExtensionComesFromParsedMimeAndCannotTraverseDirectories() {
        assertEquals("mp3", SoundRules.extensionForMime("audio/mpeg"))
        assertEquals("wav", SoundRules.extensionForMime("audio/raw"))
        assertEquals("m4a", SoundRules.extensionForMime("audio/mp4a-latm"))
        assertEquals("ogg", SoundRules.extensionForMime("audio/opus"))
        assertEquals("flac", SoundRules.extensionForMime("audio/flac"))
        assertEquals("audio", SoundRules.extensionForMime("../../secret"))
        listOf("mp3", "m4a", "wav", "ogg", "flac", "amr", "audio").forEach { assertTrue(SoundRules.isStorageExtension(it)) }
        listOf("../wav", "wav/../../", ".wav", "", "WAV").forEach { assertFalse(SoundRules.isStorageExtension(it)) }
    }
}
