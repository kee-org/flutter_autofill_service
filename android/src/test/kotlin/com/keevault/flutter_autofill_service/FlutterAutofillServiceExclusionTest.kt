package com.keevault.flutter_autofill_service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FlutterAutofillServiceExclusionTest {

    private val own = "com.consumer.app"

    @Test
    fun consumersOwnPackageIsExcludedAutomatically() {
        assertTrue(own in buildExcludedPackages(own, null))
    }

    @Test
    fun otherConsumersAreNotExcludedByAHardCodedPackage() {
        assertFalse("com.keevault.keevault" in buildExcludedPackages(own, null))
    }

    @Test
    fun builtInSystemPackagesAreExcluded() {
        val excluded = buildExcludedPackages(own, null)
        assertTrue(excluded.containsAll(listOf("android", "com.android.settings", "com.oneplus.applocker")))
    }

    @Test
    fun consumerMetaDataExtendsTheList() {
        val excluded = buildExcludedPackages(own, " com.applock.one, com.applock.two ,,")
        assertTrue("com.applock.one" in excluded)
        assertTrue("com.applock.two" in excluded)
        assertFalse("" in excluded)
        assertTrue(own in excluded)
    }

    @Test
    fun requestFromAnyExcludedSourceIsExcluded() {
        val excluded = buildExcludedPackages(own, null)
        assertTrue(isExcluded(own, emptySet(), emptySet(), excluded))
        assertTrue(isExcluded(null, setOf("com.android.settings"), emptySet(), excluded))
        assertTrue(isExcluded(null, emptySet(), setOf("com.oneplus.applocker"), excluded))
        assertFalse(isExcluded("com.other", setOf("com.other"), setOf("com.other"), excluded))
        assertFalse(isExcluded(null, emptySet(), emptySet(), excluded))
    }

    @Test
    fun exclusionIsCheckedBeforeTheSaveOnlyResponse() {
        val source = File("src/main/kotlin/com/keevault/flutter_autofill_service/FlutterAutofillService.kt").readText()
        val onFill = source.substring(source.indexOf("override fun onFillRequest"))
        val exclusion = onFill.indexOf("isExcluded(parser, excludedPackages)")
        val firstResponse = onFill.indexOf("callback.onSuccess(response)")
        assertTrue(exclusion in 0 until firstResponse)
        assertTrue(exclusion < onFill.indexOf("createSaveInfo("))
    }

    @Test
    fun loggingIsOffUntilPreferencesAreApplied() {
        assertEquals(org.tinylog.Level.OFF, DynamicLevelLoggingProvider().activeLevel)
    }
}
