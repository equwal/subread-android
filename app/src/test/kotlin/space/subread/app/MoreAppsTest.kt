package space.subread.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list of the "More apps" section. No Android, so this runs on a laptop. */
class MoreAppsTest {

    @Test
    fun theListLeavesOutThisApp() {
        assertFalse(MORE_APPS.any { it.name == "SubRead for Android" || "equwal/subread-android" in it.url })
    }

    @Test
    fun eachLinkOpensAnHttpsPage() {
        for (app in MORE_APPS) assertTrue(app.url, app.url.startsWith("https://"))
    }

    @Test
    fun theListKeepsTheOrderOfTheCatalog() {
        assertEquals(
            listOf("SubRead", "Book Simulator", "honjimaku.com", "sbm Sync"),
            MORE_APPS.take(4).map { it.name },
        )
        assertEquals("All projects", MORE_APPS.last().name)
        // The catalog has 15 entries. This app is the one that is not in the list.
        assertEquals(14, MORE_APPS.size)
    }
}
