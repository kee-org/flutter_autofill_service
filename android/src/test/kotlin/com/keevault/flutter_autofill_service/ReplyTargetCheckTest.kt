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

    private companion object {
        const val CERT_A = "14:6D:E9:AA:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB"
        const val CERT_B = "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD"
    }

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
    fun changedSigningCertificatesAreRefused() {
        val signed = bank.copy(appSigningCertSha256 = listOf(CERT_A))
        assertNull(fillTargetMismatch(signed, signed.copy()))
        val reason = fillTargetMismatch(signed, signed.copy(appSigningCertSha256 = listOf(CERT_B)))
        assertEquals("the signing certificates of the app being filled changed", reason)
        // certificates that disappear or appear between showing and filling are also a change
        assertNotNull(fillTargetMismatch(signed, signed.copy(appSigningCertSha256 = emptyList())))
        assertNotNull(fillTargetMismatch(signed.copy(appSigningCertSha256 = emptyList()), signed))
        assertNotNull(fillTargetMismatch(signed, signed.copy(appSigningCertSha256 = listOf(CERT_A, CERT_B))))
    }

    @Test
    fun changedSigningCertificateKindIsRefusedEvenWithTheSameFingerprints() {
        val single = bank.copy(appSigningCertSha256 = listOf(CERT_A, CERT_B), appSigningCertKind = AppSigningCertKind.SINGLE)
        assertNull(fillTargetMismatch(single, single.copy()))
        val reason = fillTargetMismatch(single, single.copy(appSigningCertKind = AppSigningCertKind.MULTIPLE))
        assertEquals("the signing certificates of the app being filled changed", reason)
        assertNotNull(fillTargetMismatch(single.copy(appSigningCertKind = AppSigningCertKind.MULTIPLE), single))
        assertNotNull(fillTargetMismatch(single, single.copy(appSigningCertKind = AppSigningCertKind.NONE)))
    }

    @Test
    fun signingCertificateReasonNeverNamesTheCertificates() {
        val reason = fillTargetMismatch(
            bank.copy(appSigningCertSha256 = listOf(CERT_A)),
            bank.copy(appSigningCertSha256 = listOf(CERT_B)),
        )!!
        assertEquals(false, reason.contains(CERT_A))
        assertEquals(false, reason.contains(CERT_B))
    }

    @Test
    fun metadataSigningCertificatesArePartOfTheTarget() {
        val metadata = AutofillMetadata(
            appId = "com.android.chrome",
            appIdSource = AppIdSource.COMPONENT,
            webDomains = setOf(WebDomain("https", "bank.example", dalVerified = true, focused = true)),
            appSigningCertSha256 = listOf(CERT_A),
            appSigningCertKind = AppSigningCertKind.SINGLE,
            saveInfo = null,
        )
        val current = bank.copy(appSigningCertSha256 = listOf(CERT_A), appSigningCertKind = AppSigningCertKind.SINGLE)
        assertNull(fillTargetMismatch(metadata.fillTarget(), current))
        assertNotNull(fillTargetMismatch(metadata.fillTarget(), current.copy(appSigningCertSha256 = listOf(CERT_B))))
        assertNotNull(fillTargetMismatch(metadata.fillTarget(), current.copy(appSigningCertKind = AppSigningCertKind.MULTIPLE)))
        // and they survive the trip through the launch intent's JSON
        val roundTripped = AutofillMetadata.fromJsonString(metadata.toJsonString())
        assertEquals(listOf(CERT_A), roundTripped.appSigningCertSha256)
        assertEquals(AppSigningCertKind.SINGLE, roundTripped.appSigningCertKind)
        assertEquals(true, metadata.toJsonString().contains("\"appSigningCertKind\":\"single\""))
    }

    @Test
    fun metadataJsonWithoutAKindParsesAsNone() {
        val json = "{\"appId\":\"a.b\",\"appIdSource\":\"component\",\"webDomains\":[],\"appSigningCertSha256\":[\"$CERT_A\"]}"
        val parsed = AutofillMetadata.fromJsonString(json)
        assertEquals(AppSigningCertKind.NONE, parsed.appSigningCertKind)
        assertEquals(listOf(CERT_A), parsed.appSigningCertSha256)
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
