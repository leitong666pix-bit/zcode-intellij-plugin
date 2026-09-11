package zcode.idea.ui

import com.intellij.openapi.editor.impl.DocumentImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReferenceTargetsTest {
    @Test fun duplicateBasenamesRemainSelectableAndSpecificSuffixWins() {
        val files = listOf("/project/module-a/src/Main.kt", "/project/module-b/src/Main.kt")
        assertEquals(files, ReferenceTargets.preferMatchingSuffix("Main.kt", files))
        assertEquals(listOf(files[1]), ReferenceTargets.preferMatchingSuffix("module-b/src/Main.kt", files))
    }

    @Test fun lineRangeSelectsBothEndpointsAndClampsStaleReferences() {
        val source = "first\nsecond\nthird"
        val doc = DocumentImpl(source)
        val offsets = ReferenceTargets.lineOffsets(doc, FileReference("Main.kt", 2, 3))
        assertEquals("second\nthird", source.substring(offsets.first, offsets.second))
        assertEquals(ReferenceTargets.lineOffsets(doc, FileReference("Main.kt", 3, 3)),
            ReferenceTargets.lineOffsets(doc, FileReference("Main.kt", 90, 100)))
    }
}
