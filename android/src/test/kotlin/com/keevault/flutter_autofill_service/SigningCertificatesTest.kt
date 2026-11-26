package com.keevault.flutter_autofill_service

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * The signing certificates of the requesting app let a consumer pin its identity. These tests check
 * the fingerprint format, which certificates are reported for multiple signers and for key
 * rotation, and that nothing is reported when Android hides the package.
 */
class SigningCertificatesTest {

    private companion object {
        const val PKG = "com.bank.app"
        // SHA-256 of the ASCII bytes "abc" and of the empty input, as upper case colon separated hex
        const val SHA256_ABC =
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD"
        const val SHA256_EMPTY =
            "E3:B0:C4:42:98:FC:1C:14:9A:FB:F4:C8:99:6F:B9:24:27:AE:41:E4:64:9B:93:4C:A4:95:99:1B:78:52:B8:55"
    }

    private val certA = "abc".toByteArray()
    private val certB = ByteArray(0)
    private val certC = "rotated".toByteArray()

    private fun signature(bytes: ByteArray): Signature =
        mock(Signature::class.java).also { `when`(it.toByteArray()).thenReturn(bytes) }

    private fun signingInfo(
        multiple: Boolean,
        signers: List<ByteArray>?,
        history: List<ByteArray>?,
    ): SigningInfo {
        val signerSignatures = signers?.map { signature(it) }?.toTypedArray()
        val historySignatures = history?.map { signature(it) }?.toTypedArray()
        return mock(SigningInfo::class.java).also {
            `when`(it.hasMultipleSigners()).thenReturn(multiple)
            `when`(it.apkContentsSigners).thenReturn(signerSignatures)
            `when`(it.signingCertificateHistory).thenReturn(historySignatures)
        }
    }

    private fun packageManager(info: SigningInfo?): PackageManager {
        val packageInfo = PackageInfo()
        packageInfo.signingInfo = info
        return mock(PackageManager::class.java).also {
            `when`(it.getPackageInfo(PKG, PackageManager.GET_SIGNING_CERTIFICATES)).thenReturn(packageInfo)
        }
    }

    // --- formatting ---

    @Test
    fun fingerprintIsUpperCaseColonSeparatedSha256() {
        assertEquals(SHA256_ABC, certificateSha256Fingerprint(certA))
        assertEquals(SHA256_EMPTY, certificateSha256Fingerprint(certB))
        val fingerprint = certificateSha256Fingerprint(certC)
        assertEquals(32 * 3 - 1, fingerprint.length)
        assertTrue(fingerprint.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }

    // --- selection ---

    private fun SelectedCertificates.bytes() = certificates.map { it.toList() }

    @Test
    fun multipleSignersReturnsEverySignerAsAMultipleKind() {
        val selected = selectSigningCertificates(true, listOf(certA, certB), listOf(certA))
        assertEquals(AppSigningCertKind.MULTIPLE, selected.kind)
        assertEquals(listOf(certA.toList(), certB.toList()), selected.bytes())
    }

    @Test
    fun singleSignerReturnsWholeRotationHistoryOldestFirstAsASingleKind() {
        val selected = selectSigningCertificates(false, listOf(certC), listOf(certA, certB, certC))
        assertEquals(AppSigningCertKind.SINGLE, selected.kind)
        assertEquals(listOf(certA.toList(), certB.toList(), certC.toList()), selected.bytes())
    }

    @Test
    fun singleSignerWithoutHistoryFallsBackToTheOneSigner() {
        val selected = selectSigningCertificates(false, listOf(certC), emptyList())
        assertEquals(AppSigningCertKind.SINGLE, selected.kind)
        assertEquals(listOf(certC.toList()), selected.bytes())
    }

    @Test
    fun severalSignersWithoutHistoryAreTreatedAsMultipleForTheStricterComparison() {
        // Android says not multiple but gives several signers and no lineage: inconsistent, so do
        // not let a consumer pick one of them as "current".
        val selected = selectSigningCertificates(false, listOf(certA, certC), emptyList())
        assertEquals(AppSigningCertKind.MULTIPLE, selected.kind)
        assertEquals(listOf(certA.toList(), certC.toList()), selected.bytes())
    }

    @Test
    fun duplicatesAreRemovedAndTheCurrentCertificateStaysLastForASingleSigner() {
        val selected = selectSigningCertificates(false, emptyList(), listOf(certA, certC, "abc".toByteArray()))
        assertEquals(AppSigningCertKind.SINGLE, selected.kind)
        // the repeated certificate keeps its LAST position, which is the current one
        assertEquals(listOf(certC.toList(), certA.toList()), selected.bytes())
    }

    @Test
    fun duplicateSignersAreRemovedKeepingOrderForMultipleSigners() {
        val selected = selectSigningCertificates(true, listOf(certA, certC, "abc".toByteArray()), emptyList())
        assertEquals(AppSigningCertKind.MULTIPLE, selected.kind)
        assertEquals(listOf(certA.toList(), certC.toList()), selected.bytes())
    }

    @Test
    fun nothingToSelectGivesNoneKind() {
        for (multiple in listOf(true, false)) {
            val selected = selectSigningCertificates(multiple, emptyList(), emptyList())
            assertEquals(AppSigningCertKind.NONE, selected.kind)
            assertTrue(selected.certificates.isEmpty())
        }
    }

    // --- from SigningInfo / PackageManager ---

    @Test
    fun multipleSignersAreFingerprintedFromApkContentsSigners() {
        val info = signingInfo(multiple = true, signers = listOf(certA, certB), history = null)
        assertEquals(
            SigningCertificates(AppSigningCertKind.MULTIPLE, listOf(SHA256_ABC, SHA256_EMPTY)),
            signingCertificates(info)
        )
    }

    @Test
    fun rotationHistoryIsFingerprintedForSingleSigner() {
        val info = signingInfo(multiple = false, signers = listOf(certB), history = listOf(certA, certB))
        assertEquals(
            SigningCertificates(AppSigningCertKind.SINGLE, listOf(SHA256_ABC, SHA256_EMPTY)),
            signingCertificates(info)
        )
    }

    @Test
    fun missingSignerArraysGiveNothing() {
        val info = signingInfo(multiple = false, signers = null, history = null)
        assertEquals(SigningCertificates.NONE, signingCertificates(info))
    }

    @Test
    fun installedVisiblePackageIsFingerprinted() {
        val pm = packageManager(signingInfo(false, listOf(certA), listOf(certA)))
        assertEquals(
            SigningCertificates(AppSigningCertKind.SINGLE, listOf(SHA256_ABC)),
            signingCertificates(pm, PKG, sdkInt = 30)
        )
    }

    @Test
    fun packageNotVisibleGivesEmptyList() {
        val pm = mock(PackageManager::class.java)
        `when`(pm.getPackageInfo(PKG, PackageManager.GET_SIGNING_CERTIFICATES))
            .thenThrow(PackageManager.NameNotFoundException(PKG))
        assertEquals(SigningCertificates.NONE, signingCertificates(pm, PKG, sdkInt = 30))
    }

    @Test
    fun anyOtherFailureGivesEmptyList() {
        val pm = mock(PackageManager::class.java)
        `when`(pm.getPackageInfo(PKG, PackageManager.GET_SIGNING_CERTIFICATES)).thenThrow(SecurityException("denied"))
        assertEquals(SigningCertificates.NONE, signingCertificates(pm, PKG, sdkInt = 30))
    }

    @Test
    fun noSigningInfoOrPackageManagerGivesEmptyList() {
        assertEquals(SigningCertificates.NONE, signingCertificates(packageManager(null), PKG, sdkInt = 30))
        assertEquals(SigningCertificates.NONE, signingCertificates(null, PKG, sdkInt = 30))
    }
}
