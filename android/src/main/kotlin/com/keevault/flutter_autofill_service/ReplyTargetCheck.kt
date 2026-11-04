package com.keevault.flutter_autofill_service

/**
 * What decides where a reply's credentials go: the app being filled and the domain whose fields
 * are filled ([AssistStructureParser.fillableFields] only fills fields of the focused domain).
 */
internal data class FillTarget(
        val appId: String?,
        val appIdSource: AppIdSource,
        /** Lower case host of the focused web domain, or null if there is none. */
        val focusedHost: String?,
)

/** The target the consumer was shown, from the metadata in the launch intent. */
internal fun AutofillMetadata.fillTarget() = FillTarget(
        appId = appId,
        appIdSource = appIdSource,
        focusedHost = webDomains.firstOrNull { it.focused }?.domain?.lowercase(),
)

/**
 * The target a reply built from this structure would fill. Uses the focused entry of
 * [AssistStructureParser.webDomains] rather than [AssistStructureParser.primaryHost] so that it is
 * computed exactly like the metadata the consumer was shown (on Android 11 and below both are
 * empty for non-browser apps).
 */
internal fun AssistStructureParser.fillTarget() = FillTarget(
        appId = appId,
        appIdSource = appIdSource,
        focusedHost = webDomains.firstOrNull { it.focused }?.domain?.lowercase(),
)

/**
 * Why a reply must not be sent, or null if it may be.
 *
 * The consumer chooses credentials from the [AutofillMetadata] in the launch intent, which the
 * service computed when the fill request arrived. The reply is built from the AssistStructure that
 * Android attaches to the authentication intent, which reflects the screen when the user tapped
 * the dataset and can differ (focus moved to another frame, the page navigated, the app changed
 * what it claims). Filling would then deliver the chosen credentials to a target the user never
 * approved, so the reply is refused unless both describe the same target. Missing metadata is
 * treated as a mismatch.
 *
 * The returned reason is safe to log: it never contains package names or hosts.
 */
internal fun fillTargetMismatch(shown: FillTarget?, current: FillTarget): String? = when {
    shown == null -> "no metadata describes the target the user was shown"
    shown.appId != current.appId || shown.appIdSource != current.appIdSource -> "the app being filled changed"
    shown.focusedHost != current.focusedHost -> "the focused web domain changed"
    else -> null
}
