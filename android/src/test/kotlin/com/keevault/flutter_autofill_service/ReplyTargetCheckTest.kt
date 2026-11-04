package com.keevault.flutter_autofill_service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A reply is built from the structure in the authentication intent, which can differ from the one
 * the consumer was shown. These tests check that a reply is refused unless both describe the same
 * app and focused domain, and that the refusal reason names neither.
 */
class ReplyTargetCheckTest {

    private val bank = FillTarget("com.android.chrome", AppIdSource.COMPONENT, "bank.example")

    @Test
    fun sameTargetMayBeFilled() {
        assertNull(fillTargetMismatch(bank, bank.copy()))
    }

    @Test
    fun missingMetadataIsRefused() {
        assertNotNull(fillTargetMismatch(null, bank))
    }

    @Test
    fun changedFocusedDomainIsRefused() {
        assertNotNull(fillTargetMismatch(bank, bank.copy(focusedHost = "evil.example")))
        assertNotNull(fillTargetMismatch(bank, bank.copy(focusedHost = null)))
        assertNotNull(fillTargetMismatch(bank.copy(focusedHost = null), bank))
    }

    @Test
    fun changedAppIsRefused() {
        assertNotNull(fillTargetMismatch(bank, bank.copy(appId = "com.evil")))
        assertNotNull(fillTargetMismatch(bank, bank.copy(appIdSource = AppIdSource.VIEW_NODE)))
    }

    @Test
    fun reasonsNeverNameTheTarget() {
        val other = FillTarget("com.evil", AppIdSource.VIEW_NODE, "evil.example")
        for (reason in listOf(fillTargetMismatch(bank, other), fillTargetMismatch(bank, bank.copy(focusedHost = "evil.example")))) {
            assertNotNull(reason)
            for (secret in listOf("com.android.chrome", "com.evil", "bank.example", "evil.example")) {
                assertEquals(false, reason!!.contains(secret))
            }
        }
    }

    @Test
    fun metadataFocusedHostIsLowerCased() {
        val metadata = AutofillMetadata(
            appId = "com.android.chrome",
            appIdSource = AppIdSource.COMPONENT,
            webDomains = setOf(
                WebDomain("https", "Bank.Example", dalVerified = true, focused = true),
                WebDomain("https", "ads.example"),
            ),
            saveInfo = null,
        )
        assertNull(fillTargetMismatch(metadata.fillTarget(), bank))
    }
}
