package com.keevault.flutter_autofill_service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preferences cross the Dart/Kotlin boundary as JSON. Stored preferences from older plugin versions
 * have no [AutofillPreferences.dialogHeaderText] key and Dart omits the key when the value is null.
 */
class AutofillPreferencesTest {

    private fun parse(vararg entries: Pair<String, Any>): AutofillPreferences =
        requireNotNull(AutofillPreferences.fromJsonValue(mapOf(*entries)))

    @Test
    fun headerDefaultsToNull() {
        assertNull(AutofillPreferences().dialogHeaderText)
        assertNull(AutofillPreferences().effectiveDialogHeaderText)
    }

    @Test
    fun missingKeyFromExistingInstallsMeansNoHeader() {
        val prefs = parse("enableDebug" to false, "enableFillDialog" to true)
        assertNull(prefs.dialogHeaderText)
        assertNull(prefs.effectiveDialogHeaderText)
        // Other values still apply, so there is no change in behaviour for existing installs.
        assertTrue(prefs.enableFillDialog)
    }

    @Test
    fun headerIsReadFromDartJson() {
        val prefs = parse("enableDebug" to false, "dialogHeaderText" to "Tap the button below")
        assertEquals("Tap the button below", prefs.dialogHeaderText)
        assertEquals("Tap the button below", prefs.effectiveDialogHeaderText)
    }

    @Test
    fun blankHeaderIsTreatedAsNoHeader() {
        for (blank in listOf("", " ", "   ", "\n\t")) {
            val prefs = parse("enableDebug" to false, "dialogHeaderText" to blank)
            assertEquals(blank, prefs.dialogHeaderText)
            assertNull("'$blank' should not produce a header", prefs.effectiveDialogHeaderText)
        }
    }

    @Test
    fun headerTextIsNotTrimmedOrAltered() {
        val prefs = AutofillPreferences(dialogHeaderText = "  Sign in ✓  ")
        assertEquals("  Sign in ✓  ", prefs.effectiveDialogHeaderText)
    }

    @Test
    fun headerSurvivesTheJsonRoundTripUsedForStorage() {
        val original = AutofillPreferences(enableFillDialog = false, dialogHeaderText = "Ünïcode ✓")
        @Suppress("UNCHECKED_CAST")
        val json = original.toJsonValue() as Map<String, Any>
        assertEquals("Ünïcode ✓", json["dialogHeaderText"])
        val restored = requireNotNull(AutofillPreferences.fromJsonValue(json))
        assertEquals(original, restored)
    }

    @Test
    fun nullHeaderIsNotWrittenToJson() {
        @Suppress("UNCHECKED_CAST")
        val json = AutofillPreferences().toJsonValue() as Map<String, Any>
        assertFalse(json.containsKey("dialogHeaderText"))
        assertNotNull(AutofillPreferences.fromJsonValue(json))
    }

    @Test
    fun derivedPropertyIsNotPartOfTheJsonContract() {
        @Suppress("UNCHECKED_CAST")
        val json = AutofillPreferences(dialogHeaderText = "x").toJsonValue() as Map<String, Any>
        assertFalse(json.containsKey("effectiveDialogHeaderText"))
    }
}
