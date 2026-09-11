package zcode.idea.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FileSnapshotsTest {
    @TempDir lateinit var dir: Path
    private fun file(name: String, text: String) = dir.resolve(name).toFile().apply { writeText(text) }

    @Test fun completedSnapshotsStillCountAgainstBudget() {
        val store = FileSnapshots(singleLimitBytes = 20, totalLimitBytes = 20)
        val first = file("a", "12345678")
        store.capture("a", first)
        store.complete("a", first.path, "Edit", true)
        assertEquals(16L, store.retainedBytes())
        val second = file("b", "abcd")
        store.capture("b", second)
        store.complete("b", second.path, "Edit", true)
        assertEquals(16L, store.retainedBytes())
        assertTrue(store.files()[1].before is BeforeContent.Unavailable)
    }

    @Test fun repeatedEditsShareFirstSnapshotIncludingConcurrentTools() {
        val store = FileSnapshots(20, 20)
        val target = file("a", "original")
        store.capture("one", target)
        target.writeText("changed")
        store.capture("two", target)
        assertEquals(16L, store.retainedBytes())
        store.complete("one", target.path, "Edit", false)
        store.complete("two", target.path, "Edit", true)
        store.capture("three", target)
        store.complete("three", target.path, "Edit", true)
        assertEquals("original", store.files().single().oldContent)
        assertEquals(16L, store.retainedBytes())
    }

    @Test fun failedWritesReleaseBudget() {
        val store = FileSnapshots(8, 8)
        val target = file("a", "abcd")
        store.capture("failed", target)
        assertEquals(8L, store.retainedBytes())
        store.complete("failed", target.path, "Edit", false)
        assertEquals(0L, store.retainedBytes())
        assertTrue(store.files().isEmpty())
        store.capture("retry", target)
        store.complete("retry", target.path, "Edit", true)
        assertEquals("abcd", store.files().single().oldContent)
    }

    @Test fun newFileAndUnavailableSnapshotAreDistinct() {
        val store = FileSnapshots(8, 8)
        val missing = dir.resolve("new").toFile()
        store.capture("new", missing)
        store.complete("new", missing.path, "Write", true)
        val large = file("large", "12345")
        store.capture("large", large)
        store.complete("large", large.path, "Edit", true)
        store.capture("directory", dir.toFile())
        store.complete("directory", dir.toString(), "Edit", true)
        assertSame(BeforeContent.NewFile, store.files()[0].before)
        assertTrue(store.files()[1].before is BeforeContent.Unavailable)
        assertTrue(store.files()[2].before is BeforeContent.Unavailable)
    }

    @Test fun historyAndMissingCaptureNeverImplyNewFile() {
        val store = FileSnapshots()
        store.registerHistory(dir.resolve("history").toString(), "Edit")
        store.complete("unknown", dir.resolve("unknown").toString(), "Edit", true)
        assertTrue(store.files().all { it.before is BeforeContent.Unavailable })
        assertTrue(store.files()[0].fromHistory)
    }

    @Test fun emptyExistingFileRemainsCapturedAndClearReleasesAll() {
        val store = FileSnapshots(8, 8)
        val empty = file("empty", "")
        store.capture("empty", empty)
        store.complete("empty", empty.path, "Edit", true)
        assertEquals(BeforeContent.Captured(""), store.files().single().before)
        store.capture("pending", file("pending", "abcd"))
        store.clearPending()
        assertEquals(0L, store.retainedBytes())
        assertEquals(1, store.files().size)
        store.clear()
        assertTrue(store.files().isEmpty())
    }
}
