package top.steeefanie.leafreader.drive

import org.junit.Assert.*
import org.junit.Test
import top.steeefanie.leafreader.storage.*

class StateMergeTest {
    private val hash = "a".repeat(64)
    private fun position(page: Int, time: Long) = Progress(hash, "epub", """{"href":"chapter.xhtml","type":"application/xhtml+xml","locations":{"position":$page}}""", time)
    private fun state(p: Progress?, vararg marks: Bookmark) = Exchange(listOfNotNull(p), marks.toList())
    private fun mark(id: String, time: Long, deleted: Boolean = false) = Bookmark(hash, id, "epub", id, position(1, 1).locator, time, deleted)
    @Test fun sameTimeBookmarkRenamePreservesLocalConflictCopy() {
        val base = state(null, mark("one", 50))
        val local = state(null, mark("one", 100).copy(label="本机名称"))
        val remote = state(null, mark("one", 100).copy(label="远端名称"))
        val result = StateMerge.merge(base, local, remote)
        assertEquals(remote, result.state); assertEquals(local, result.localConflict)
    }
    @Test fun sameTimeIdenticalBookmarkAndDeletionPriorityNeedNoConflictCopy() {
        val same = state(null, mark("one", 100))
        assertNull(StateMerge.merge(BookState.empty, same, same).localConflict)
        val deleted = state(null, mark("one", 100, true))
        val result = StateMerge.merge(BookState.empty, same, deleted)
        assertTrue(result.state.bookmarks.single().deleted); assertNull(result.localConflict)
    }
    @Test fun unchangedLocalBookmarkDoesNotBecomeAConflict() {
        val base=state(null, mark("one",100))
        val remote=state(null, mark("one",100).copy(label="仅远端变化"))
        val result=StateMerge.merge(base,base,remote)
        assertEquals(remote,result.state);assertNull(result.localConflict)
    }
    @Test fun onlyRemoteChangesAdoptRemoteEvenWhenItsClockIsOlder() {
        val base = state(position(1, 100)); val remote = state(position(2, 50))
        assertEquals(remote, StateMerge.merge(base, base, remote).state)
    }
    @Test fun onlyLocalChangesRetainLocal() {
        val base = state(position(1, 100)); val local = state(position(2, 150))
        assertEquals(local, StateMerge.merge(base, local, base).state)
    }
    @Test fun newerTimestampWinsEvenWhenUserReadsBackwards() {
        val base = state(position(1, 1)); val local = state(position(2, 300)); val remote = state(position(8, 200))
        assertEquals(local, StateMerge.merge(base, local, remote).state)
    }
    @Test fun equalTimeDivergenceUsesRemoteAndPreservesLocalCopy() {
        val local = state(position(2, 100)); val remote = state(position(3, 100))
        val result = StateMerge.merge(BookState.empty, local, remote)
        assertEquals(remote, result.state); assertEquals(local, result.localConflict)
    }
    @Test fun tombstoneWinsEqualTimeAndUnrelatedBookmarksSurvive() {
        val result = StateMerge.merge(BookState.empty,
            state(null, mark("one", 100), mark("two", 100)), state(null, mark("one", 100, true), mark("three", 200))).state
        assertEquals(3, result.bookmarks.size); assertTrue(result.bookmarks.first { it.id == "one" }.deleted)
    }
    @Test fun offlineDeletionIsNotResurrectedByUnchangedOldRemote() {
        val base = state(null, mark("one", 100))
        val local = state(null, mark("one", 200, true))
        assertTrue(StateMerge.merge(base, local, base).state.bookmarks.single().deleted)
    }
    @Test fun singleBookDocumentRejectsOtherHashes() {
        val invalid = Exchange(listOf(position(1, 1).copy(hash = "b".repeat(64))), emptyList())
        assertThrows(IllegalArgumentException::class.java) { BookState.decode(hash, BookState.encode(invalid)) }
    }
    @Test fun locatorKeyOrderDoesNotCreateFalseConflict() {
        val first = state(position(1, 1))
        val second = state(position(1, 1).copy(locator = """{"locations":{"position":1},"type":"application/xhtml+xml","href":"chapter.xhtml"}"""))
        assertTrue(BookState.equivalent(first, second))
    }
}
