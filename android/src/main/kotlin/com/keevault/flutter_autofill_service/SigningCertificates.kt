package com.keevault.flutter_autofill_service

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * Formats the SHA-256 digest of [certificate] (a DER encoded X.509 certificate, as returned by
 * `Signature.toByteArray()`) the way `sha256_cert_fingerprints` appears in `assetlinks.json`:
 * upper case hex pairs separated by colons, for example `14:6D:E9:...`.
 */
internal fun certificateSha256Fingerprint(certificate: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(certificate)
        .joinToString(":") { "%02X".format(it) }

/**
 * The shape of an app's signing identity. Android's `SigningInfo` has two mutually exclusive
 * shapes and a consumer must treat them differently, so the list of fingerprints alone is not
 * enough to pin an app safely.
 */
@JsonClass(generateAdapter = false)
enum class AppSigningCertKind {
    /** Nothing is known: not a trusted component package, not visible to us, or lookup failed. */
    @Json(name = "none") NONE,

    /**
     * One signer. The fingerprints are its key rotation lineage (APK Signature Scheme v3), oldest
     * first with the certificate currently used to sign the app LAST. An app that never rotated
     * has exactly one. A consumer pins the current (last) certificate and accepts it anywhere in
     * a later lineage (the app rotated forward) but not an older lineage that lacks it (rollback).
     */
    @Json(name = "single") SINGLE,

    /**
     * Several independent signers, all of which sign the APK. The fingerprints are those signers
     * in no meaningful order, to be treated as a SET. Android does not support key rotation for
     * these, so a consumer should require the set to be exactly equal to the pinned one.
     */
    @Json(name = "multiple") MULTIPLE,
}

/** [kind] and the matching [sha256] fingerprints (see [certificateSha256Fingerprint]). */
internal data class SigningCertificates(
    val kind: AppSigningCertKind,
    val sha256: List<String>,
) {
    companion object {
        val NONE = SigningCertificates(AppSigningCertKind.NONE, emptyList())
    }
}

/** Raw certificates chosen by [selectSigningCertificates]. */
internal class SelectedCertificates(val kind: AppSigningCertKind, val certificates: List<ByteArray>)

/**
 * Chooses which certificates describe the identity of an app, and in which shape.
 *
 * - If the APK has [hasMultipleSigners] (several independent signers, all required) the result is
 *   [AppSigningCertKind.MULTIPLE] holding every one of [apkContentsSigners], in the order Android
 *   reports them.
 * - Otherwise, if [signingCertificateHistory] is not empty the result is
 *   [AppSigningCertKind.SINGLE] holding the whole proof-of-rotation lineage of the single signer
 *   (APK Signature Scheme v3), oldest first with the certificate currently used to sign the app
 *   last. An app that never rotated has a history of exactly one certificate.
 * - If Android reports no history (unexpected for a single signer) the signers are used: exactly
 *   one signer is SINGLE with that certificate as current, and more than one is MULTIPLE (the
 *   stricter set comparison) even though Android did not say so.
 * - Nothing to report is [AppSigningCertKind.NONE].
 *
 * Duplicates are removed. For SINGLE the last occurrence is kept so that the last entry is always
 * the current certificate; otherwise the first occurrence is kept.
 */
internal fun selectSigningCertificates(
    hasMultipleSigners: Boolean,
    apkContentsSigners: List<ByteArray>,
    signingCertificateHistory: List<ByteArray>,
): SelectedCertificates {
    fun List<ByteArray>.distinctKeepingFirst() = distinctBy { it.toList() }
    fun List<ByteArray>.distinctKeepingLast() = asReversed().distinctBy { it.toList() }.asReversed()
    return when {
        hasMultipleSigners -> apkContentsSigners.distinctKeepingFirst().let {
            SelectedCertificates(if (it.isEmpty()) AppSigningCertKind.NONE else AppSigningCertKind.MULTIPLE, it)
        }
        signingCertificateHistory.isNotEmpty() ->
            SelectedCertificates(AppSigningCertKind.SINGLE, signingCertificateHistory.distinctKeepingLast())
        else -> apkContentsSigners.distinctKeepingFirst().let {
            SelectedCertificates(
                when (it.size) {
                    0 -> AppSigningCertKind.NONE
                    1 -> AppSigningCertKind.SINGLE
                    else -> AppSigningCertKind.MULTIPLE
                },
                it
            )
        }
    }
}

/** The certificates (see [selectSigningCertificates]) of [signingInfo], fingerprinted. */
internal fun signingCertificates(signingInfo: SigningInfo): SigningCertificates {
    val multiple = signingInfo.hasMultipleSigners()
    val signers = signingInfo.apkContentsSigners.orEmpty().map { it.toByteArray() }
    val history = if (multiple) {
        emptyList()
    } else {
        signingInfo.signingCertificateHistory.orEmpty().map { it.toByteArray() }
    }
    val selected = selectSigningCertificates(multiple, signers, history)
    return SigningCertificates(selected.kind, selected.certificates.map(::certificateSha256Fingerprint))
}

/**
 * The signing certificates of the installed [packageName], or [SigningCertificates.NONE] if they
 * cannot be determined: the package is not installed or, on Android 11+, not visible
 * to this app (which needs the QUERY_ALL_PACKAGES permission for packages that do not match a
 * `<queries>` entry; see the README).
 *
 * Only call this with a package that Android itself supplied (the activity component), never one
 * the app being filled named in its own view hierarchy.
 */
internal fun signingCertificates(
    packageManager: PackageManager?,
    packageName: String,
    sdkInt: Int = Build.VERSION.SDK_INT,
): SigningCertificates {
    return try {
        if (packageManager == null) {
            logger.warn { "Signing certificates unavailable: no package manager." }
            return SigningCertificates.NONE
        }
        val packageInfo: PackageInfo? = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val signingInfo = packageInfo?.signingInfo
        if (signingInfo == null) {
            logger.warn { "No signing certificate information is available for the requesting app." }
            SigningCertificates.NONE
        } else {
            signingCertificates(signingInfo)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        // Not installed, or not visible to us (package visibility filtering, API 30+).
        logger.warn { "Signing certificates unavailable: the requesting app is not installed or not visible to this app. Have you declared QUERY_ALL_PACKAGES? See the README." }
        SigningCertificates.NONE
    } catch (e: Exception) {
        logger.warn { "Signing certificates could not be read: ${e.javaClass.simpleName}" }
        SigningCertificates.NONE
    }
}
