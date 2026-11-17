package com.keevault.flutter_autofill_service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The fill dialog views are inflated by the system UI process through RemoteViews, so they can only
 * use a restricted set of views and cannot resolve theme attributes reliably. Nothing here can catch
 * a visual problem, but it does stop the most likely RemoteViews breakages from being merged.
 */
class FillDialogResourcesTest {

    private val resDir: File = listOf(File("src/main/res"), File("android/src/main/res"))
        .first { it.isDirectory }

    private fun parse(relativePath: String): Document {
        val file = File(resDir, relativePath)
        assertTrue("$file should exist", file.isFile)
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder().parse(file)
    }

    private val allowedViews = setOf("LinearLayout", "TextView", "ImageView", "FrameLayout")

    private fun elements(doc: Document): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(n: Node) {
            if (n is Element) out += n
            for (i in 0 until n.childNodes.length) walk(n.childNodes.item(i))
        }
        walk(doc.documentElement)
        return out
    }

    private fun assertRemoteViewsSafe(layout: String) {
        val text = File(resDir, layout).readText()
        assertFalse("$layout must not use theme attributes", text.contains("?attr/") || text.contains("?android:attr/"))
        for (e in elements(parse(layout))) {
            assertTrue("$layout uses unsupported view <${e.tagName}>", e.tagName in allowedViews)
        }
    }

    private fun idsIn(layout: String): Set<String> =
        elements(parse(layout)).mapNotNull { it.getAttribute("android:id").takeIf { id -> id.isNotEmpty() } }.toSet()

    @Test
    fun unlockButtonLayoutIsRemoteViewsSafe() = assertRemoteViewsSafe("layout/fill_dialog_unlock_item.xml")

    @Test
    fun headerLayoutIsRemoteViewsSafe() = assertRemoteViewsSafe("layout/fill_dialog_header.xml")

    @Test
    fun unlockButtonLayoutHasTheIdsTheHelperPopulates() {
        // RemoteViewsHelper.fillDialogUnlock sets R.id.text and R.id.icon.
        val ids = idsIn("layout/fill_dialog_unlock_item.xml")
        assertTrue(ids.contains("@+id/text"))
        assertTrue(ids.contains("@+id/icon"))
    }

    @Test
    fun headerLayoutRootIsTheTextViewTheHelperPopulates() {
        val root = parse("layout/fill_dialog_header.xml").documentElement
        assertEquals("TextView", root.tagName)
        assertEquals("@+id/text", root.getAttribute("android:id"))
    }

    @Test
    fun unlockButtonHasAccessibleMinimumTouchHeight() {
        val root = parse("layout/fill_dialog_unlock_item.xml").documentElement
        assertEquals("48dp", root.getAttribute("android:minHeight"))
    }

    @Test
    fun decorativeIconsAreHiddenFromAccessibility() {
        val images = elements(parse("layout/fill_dialog_unlock_item.xml")).filter { it.tagName == "ImageView" }
        assertEquals(2, images.size)
        images.forEach { assertEquals("no", it.getAttribute("android:importantForAccessibility")) }
    }

    @Test
    fun buttonBackgroundIsAPlainShape() {
        // ripple and selector drawables are less reliably supported across RemoteViews hosts.
        assertEquals("shape", parse("drawable/flutter_autofill_fill_dialog_button_bg.xml").documentElement.tagName)
    }

    @Test
    fun overridableColoursAreDefinedAndUsedByTheViews() {
        val names = elements(parse("values/colors.xml")).filter { it.tagName == "color" }
            .map { it.getAttribute("name") }.toSet()
        val expected = setOf(
            "flutter_autofill_fill_dialog_button",
            "flutter_autofill_fill_dialog_button_text",
            "flutter_autofill_fill_dialog_header_text",
        )
        assertEquals(expected, names)
        // Every colour reference in the new resources must be to one that is defined above.
        val referenced = listOf(
            "layout/fill_dialog_unlock_item.xml",
            "layout/fill_dialog_header.xml",
            "drawable/flutter_autofill_fill_dialog_button_bg.xml",
            "drawable/ic_fill_dialog_chevron_24dp.xml",
        ).flatMap { Regex("@color/([A-Za-z0-9_]+)").findAll(File(resDir, it).readText()).map { m -> m.groupValues[1] } }
        assertTrue(referenced.isNotEmpty())
        assertTrue(expected.containsAll(referenced))
    }

    @Test
    fun colourResourcesAreNamespacedSoTheyCannotClashWithTheHostApp() {
        val names = elements(parse("values/colors.xml")).filter { it.tagName == "color" }
            .map { it.getAttribute("name") }
        names.forEach { assertTrue(it.startsWith("flutter_autofill_")) }
    }

    @Test
    fun existingDropdownLayoutIsNotUsedForTheDialog() {
        // The dropdown row stays as it was; the dialog has its own layouts.
        assertTrue(File(resDir, "layout/multidataset_service_list_item.xml").isFile)
        assertFalse(idsIn("layout/fill_dialog_unlock_item.xml").isEmpty())
    }
}
