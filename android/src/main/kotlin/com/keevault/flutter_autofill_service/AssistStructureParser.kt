package com.keevault.flutter_autofill_service

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.*
import android.view.*
import android.view.autofill.AutofillId
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import io.github.oshai.kotlinlogging.KotlinLogging
import android.app.assist.AssistStructure.ViewNode

private val logger = KotlinLogging.logger {}

/**
 * A web domain claimed by the app being filled. The claim itself is untrusted: only [dalVerified]
 * says whether the app that presented it is known to be entitled to.
 */
@JsonClass(generateAdapter = true)
data class WebDomain(
        val scheme: String?,
        val domain: String,
        /**
         * True if the app presenting the request is a known browser, or Android reports it as
         * verified (or user-selected) to handle links for exactly this domain.
         */
        val dalVerified: Boolean = false,
        /**
         * True for the one domain that the request is about (the domain of the focused field),
         * so consumers don't have to guess which of several claimed domains is the primary one.
         * At most one domain is focused and none are if the primary domain cannot be determined.
         */
        val focused: Boolean = false
)

/**
 * Where [AssistStructureParser.appId] came from. Only [COMPONENT] is supplied by the system; the
 * app being filled controls the others.
 */
@JsonClass(generateAdapter = false)
enum class AppIdSource {
    /** The activity component in the AssistStructure, supplied by Android. */
    @Json(name = "component") COMPONENT,
    /** The idPackage of a view node, chosen by the app being filled. */
    @Json(name = "viewNode") VIEW_NODE,
    /** The window title, chosen by the app being filled. */
    @Json(name = "windowTitle") WINDOW_TITLE,
    /** No app could be identified. */
    @Json(name = "none") NONE,
}

class AssistStructureParser(
        structure: AssistStructure,
        private val context: Context,
        private val sdkInt: Int = Build.VERSION.SDK_INT
) {

    val autoFillIds = mutableListOf<AutofillId>()
    val allNodes = mutableListOf<ViewNode>()

    var componentPackage: String? = null
    val nodeIdPackages = HashSet<String>()
    val titlePackages = HashSet<String>()

    /** Domains claimed by view nodes, keyed by lower case host. Unverified and not focused. */
    private val claimedDomains = LinkedHashMap<String, WebDomain>()

    /**
     * Every claimed domain with its individual verification status. Empty on Android 11 and
     * below unless the app is a known browser (see [isWebOrigin]).
     */
    var webDomains: Set<WebDomain> = emptySet()
        private set

    /**
     * Lower case host of the domain the request is about, or null if there is none. Decided by,
     * in order: the focused credential field, the only claimed domain, the password field.
     * Fields are only filled if they belong to this domain, or to no domain when this is null.
     */
    var primaryHost: String? = null
        private set

    var appId: String? = null
    var appIdSource: AppIdSource = AppIdSource.NONE
        private set

    /** True only if [appId] came from the system-supplied activity component. */
    val appIdTrusted: Boolean get() = appIdSource == AppIdSource.COMPONENT

    /**
     * True if [appId] is a web browser, so it must not be treated as the identity of an app.
     */
    var appIsBrowser: Boolean = false
        private set

    var isWebOrigin: Boolean = false
        private set

    val fieldIds =
            mutableMapOf<AutofillInputType, MutableList<MatchedField>>()

    var focusedAutofillId: AutofillId? = null

    private val excludedPackageIds: List<String> = listOf("android")


    private val trustedNativeBrowsers: List<String> = listOf(
            "com.duckduckgo.mobile.android",
            "org.mozilla.focus",
            "org.mozilla.klar",
            "com.android.chrome",
            "org.chromium.chrome",
            "com.chrome.beta",
            "com.chrome.canary",
            "com.chrome.dev",
    )

    private val trustedCompatBrowsers: List<String> = listOf(
            "acr.browser.lightning",
            "acr.browser.barebones",
            "alook.browser",
            "alook.browser.google",
            "com.amazon.cloud9",
            "com.android.browser",
            "com.android.htmlviewer",
            "com.avast.android.secure.browser",
            "com.avg.android.secure.browser",
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.brave.browser_default",
            "com.brave.browser_dev",
            "com.brave.browser_nightly",
            "com.ecosia.android",
            "com.google.android.apps.chrome",
            "com.google.android.apps.chrome_dev",
            "com.google.android.captiveportallogin",
            "com.kiwibrowser.browser",
            "com.kiwibrowser.browser.dev",
            "com.microsoft.emmx",
            "com.mmbox.browser",
            "com.mmbox.xbrowser",
            "com.naver.whale",
            "com.opera.browser",
            "com.opera.browser.beta",
            "com.opera.mini.native",
            "com.opera.mini.native.beta",
            "com.opera.touch",
            "com.qflair.browserq",
            "com.qwant.liberty",
            "com.sec.android.app.sbrowser",
            "com.sec.android.app.sbrowser.beta",
            "com.stoutner.privacybrowser.free",
            "com.stoutner.privacybrowser.standard",
            "com.vivaldi.browser",
            "com.vivaldi.browser.snapshot",
            "com.vivaldi.browser.sopranos",
            "com.yandex.browser",
            "com.z28j.feel",
            "idm.internet.download.manager",
            "idm.internet.download.manager.adm.lite",
            "idm.internet.download.manager.plus",
            "io.github.forkmaintainers.iceraven",
            "jp.hazuki.yuzubrowser",
            "mark.via",
            "mark.via.gp",
            "net.slions.fulguris.full.download",
            "net.slions.fulguris.full.download.debug",
            "net.slions.fulguris.full.playstore",
            "net.slions.fulguris.full.playstore.debug",
            "org.adblockplus.browser",
            "org.adblockplus.browser.beta",
            "org.bromite.bromite",
            "org.bromite.chromium",
            "org.codeaurora.swe.browser",
            "org.gnu.icecat",
            "org.mozilla.fenix",
            "org.mozilla.fenix.nightly",
            "org.mozilla.fennec_aurora",
            "org.mozilla.fennec_fdroid",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.reference.browser",
            "org.mozilla.rocket",
            "org.torproject.torbrowser",
            "org.torproject.torbrowser_alpha",
            "org.ungoogled.chromium.extensions.stable",
            "org.ungoogled.chromium.stable",
            "us.spotco.fennec_dos",
    )

    init {
        traverseStructure(structure)
        val componentPkg = structure.activityComponent?.packageName
        logger.debug { "component package ID: $componentPkg" }
        componentPkg?.let {
            componentPackage = it
        }
        normaliseParsedStructure()
    }

    /**
     * The fields it is safe to write credentials into, highest weight first. Fields that belong to
     * a different web domain from [primaryHost] are left out, so credentials chosen for one
     * origin are not delivered to fields of another origin (for example native fields in the
     * host app, or other WebViews and iframes). Fields the user cannot see are also left out.
     */
    fun fillableFields(): List<Pair<AutofillInputType, MatchedField>> =
        fieldIds.flatMap { entry -> entry.value.map { entry.key to it } }
            .filter { (type, field) ->
                val fillable = field.isVisible && field.webHost == primaryHost
                if (!fillable) {
                    logger.debug { "Not filling $type field ${field.autofillId}: visible=${field.isVisible}, host=${field.webHost}, primaryHost=$primaryHost" }
                }
                fillable
            }
            .sortedByDescending { it.second.heuristic.weight }

    private fun normaliseParsedStructure() {
        componentPackage?.let {
            if (excludedPackageIds.contains(it)) {
                componentPackage = null
            }
        }
        nodeIdPackages.removeAll(excludedPackageIds)
        titlePackages.removeAll(excludedPackageIds)
        titlePackages.removeIf() { p -> p.startsWith("PopupWindow:") }

        // Determine appId with priority: componentPackage (packageName) > nodeIdPackages (idPackage) > titlePackages (title)
        when {
            componentPackage != null -> {
                appId = componentPackage
                appIdSource = AppIdSource.COMPONENT
            }
            nodeIdPackages.isNotEmpty() -> {
                // idPackage is set by the app being filled (ViewStructure.setId) so it is only a
                // best guess, like the window title. With several values the pick is arbitrary.
                appId = nodeIdPackages.firstOrNull()
                appIdSource = AppIdSource.VIEW_NODE
            }
            titlePackages.isNotEmpty() -> {
                appId = titlePackages.firstOrNull()
                appIdSource = AppIdSource.WINDOW_TITLE
            }
            else -> {
                appId = null
                appIdSource = AppIdSource.NONE
            }
        }

        appIsBrowser = appId?.let { isBrowser(it) } ?: false
        primaryHost = determinePrimaryHost()
        verifyDigitalAssetLinks()
    }

    private val knownBrowsers: List<String> get() = trustedCompatBrowsers + trustedNativeBrowsers

    private fun isBrowser(packageName: String): Boolean {
        if (packageName in knownBrowsers) return true
        // Catches browsers missing from the allowlist. Apps that only handle their own hosts do
        // not match a made-up host, so ordinary (including WebView) apps are not caught. Needs the
        // matching <queries> entry in the manifest for API 30+; without it browsers are not
        // visible and this returns false, leaving the allowlist as the only signal.
        return try {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val handlers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            }
            handlers.any { it.activityInfo?.packageName == packageName }
        } catch (e: Exception) {
            logger.warn { "Browser detection query failed: ${e.message}" }
            false
        }
    }

    private fun determinePrimaryHost(): String? {
        val focusedFields = focusedAutofillId?.let { id ->
            fieldIds.values.flatten().filter { it.autofillId == id }
        }.orEmpty()
        if (focusedFields.isNotEmpty()) {
            return focusedFields.first().webHost
        }
        if (claimedDomains.size == 1) {
            return claimedDomains.keys.first()
        }
        return (fieldIds[AutofillInputType.Password].orEmpty() + fieldIds[AutofillInputType.NewPassword].orEmpty())
            .maxByOrNull { it.heuristic.weight }?.webHost
    }

    private fun verifyDigitalAssetLinks() {
        isWebOrigin = claimedDomains.isNotEmpty()
        if (!isWebOrigin) {
            // No web origin means there is nothing to verify against.
            return
        }
        // Only componentPackage may be used below. It comes from AssistStructure.activityComponent,
        // which the system fills in. Every other package string in the structure (nodeIdPackages,
        // titlePackages) is chosen by the app being filled, e.g. via ViewStructure.setId(), so
        // using one of them here would let a malicious app claim to be a browser or another app.
        val trustedPackage = componentPackage

        // Each claimed domain is verified on its own. A single verified domain says nothing
        // about the others because the app being filled can claim any number of them.
        val verifiedHosts = mutableSetOf<String>()

        if (trustedPackage != null && trustedPackage in knownBrowsers) {
            // Known trusted browsers render web content legitimately and are not claiming
            // to own the domains they display, so they are always considered verified.
            // This short-circuits before the DAL check, otherwise common cases like Chrome
            // loading an arbitrary site would fail verification (browsers don't publish
            // assetlinks.json for every domain they render).
            verifiedHosts.addAll(claimedDomains.keys)
            logger.debug { "DAL: trusted browser allowlist hit for component=$trustedPackage, all domains verified" }
        } else if (sdkInt >= Build.VERSION_CODES.S) {
            // A non-browser app presenting a web origin is suspicious: verify that it
            // actually owns the domain via Digital Asset Links. This is the AutoFail
            // threat model — an app masquerading as a site the user has credentials for.
            if (trustedPackage == null) {
                // Without a system-provided component there is no package we can trust to
                // have been the one that presented these domains, so fail closed.
                logger.warn { "DAL verification FAILED for web origins ${claimedDomains.keys}: no system-provided component package" }
            } else {
                try {
                    val manager = context.getSystemService(Context.DOMAIN_VERIFICATION_SERVICE) as DomainVerificationManager
                    val hostMap = manager.getDomainVerificationUserState(trustedPackage)?.getHostToStateMap()
                    if (hostMap != null) {
                        for ((host, claim) in claimedDomains) {
                            val state = hostMap[claim.domain] ?: hostMap[host]
                            if (state == DomainVerificationUserState.DOMAIN_STATE_VERIFIED ||
                                    state == DomainVerificationUserState.DOMAIN_STATE_SELECTED) {
                                verifiedHosts.add(host)
                                logger.debug { "DAL verified: $trustedPackage is verified/selected for domain ${claim.domain} (state=$state)" }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn { "DAL verification check failed for package $trustedPackage: ${e.message}" }
                }
                val unverified = claimedDomains.keys - verifiedHosts
                if (unverified.isNotEmpty()) {
                    logger.warn { "DAL verification FAILED for web origins $unverified from component=$trustedPackage" }
                }
            }
        } else {
            // API 29-30: no public DAL query API exists (DomainVerificationManager is API 31+,
            // and GET_DOMAIN_VERIFICATION / PackageInfo.domainVerificationInfo are @hide).
            // Preserve prior behaviour: report no web origins so unverified non-browser
            // apps cannot match saved credentials.
            logger.debug { "API < 31, no DAL query API available; stripped web origins for non-browser component=$trustedPackage" }
            return
        }

        webDomains = claimedDomains.map { (host, claim) ->
            claim.copy(dalVerified = host in verifiedHosts, focused = host == primaryHost)
        }.toSet()
    }

    private fun traverseStructure(structure: AssistStructure) {
        val windowNodes: List<AssistStructure.WindowNode> =
                structure.run {
                    (0 until windowNodeCount).map { getWindowNodeAt(it) }
                }

        logger.debug { "Traversing windowNodes $windowNodes" }
        windowNodes.forEach { windowNode: AssistStructure.WindowNode ->
            logger.debug { "windowNode title ${windowNode.title}" }
            val titlePackage = extractPackageFromTitle(windowNode)
            logger.debug { "title package ID: $titlePackage" }
            titlePackage?.let { titlePackages.add(it) }
            windowNode.rootViewNode?.let { traverseNode(it, "", null, null, true) }
        }
    }

    private fun extractPackageFromTitle(windowNode: AssistStructure.WindowNode): String? {
        return windowNode.title.takeUnless { it.isNullOrBlank() }?.split('/')?.firstOrNull()
    }

    private fun Any.debugToString(): String =
            when (this) {
                is Array<*> -> this.contentDeepToString()
                is Bundle -> keySet().map {
                    it to get(it)?.toString()
                }.toString()
                is ViewStructure.HtmlInfo -> "HtmlInfo{<$tag ${attributes?.filter { it.first?.lowercase() != "value" }?.joinToString(" ") { "${it.first}=\"${it.second}\"" }}>}"
                else -> this.toString()
            }

//TODO: this, if we ever have time to set up a system to track potential malicious apps masquerading as an app the user has credentials for. Really shouldn't be our problem though - Android should handle such things transparently! Maybe one day...
// Also, Android 11+ restricts what package information we can find so it's likely this is impossible to achieve without demanding QUERY_ALL_PACKAGES permission. It's undocumented though, so perhaps packageManager.getPackageInfo is an exception to that requirement.
    // private fun getSignatures(packageName: String) {
    //     val signature: Signature;
    //         if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
    //             @Suppress("DEPRECATION")
    //             @SuppressLint("PackageManagerGetSignatures")
    //             val packageInfo: PackageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    //             @Suppress("DEPRECATION")
    //             signature = packageInfo.signatures.first()
    //             return signature.toByteArray() //TODO: return an array and update non-deprecated version; add array of sigs to data sent back to dart
    //         } else {
    //             val packageInfo: PackageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    //             signature = packageInfo.signingInfo.apkContentsSigners.first()
    //             result.success(signature.toByteArray())
    //         }
    // }

    /**
     * @param inheritedDomain the web domain of the nearest ancestor that has one, which also
     *        applies to this node unless it declares its own
     * @param ancestorsVisible false if any ancestor is not visible, which hides this node too
     */
    private fun traverseNode(viewNode: ViewNode, depth: String, inheritedDomain: String?, inheritedScheme: String?, ancestorsVisible: Boolean) {
        allNodes.add(viewNode)
        val debug =
                (listOf(
                        viewNode::getId,
                        viewNode::getAutofillId,
                        viewNode::getClassName,
                        viewNode::getWebDomain,
                        viewNode::getAutofillId,
                        viewNode::getAutofillHints,
                        viewNode::getAutofillOptions,
                        viewNode::getAutofillType,
                        viewNode::getHint,
                        viewNode::getIdEntry,
                        viewNode::getIdPackage,
                        viewNode::getIdType,
                        viewNode::getInputType,
                        viewNode::getContentDescription,
                        viewNode::getHtmlInfo,
                        viewNode::getExtras
                ) +
                        listOf(
                                viewNode::getWebScheme,
                                viewNode::getTextIdEntry,
                                viewNode::getImportantForAutofill
                        ))
                        .map { it.name.replaceFirst("get", "") to it.invoke()?.debugToString() }
        logger.trace { "$depth ` ViewNode: ${debug.filter { it.second != null }.toList()}" }
        logger.trace { "$depth     We got autofillId: ${viewNode.autofillId} autofillOptions:${viewNode.autofillOptions} autofillType:${viewNode.autofillType} hints:${viewNode.autofillHints?.contentToString()} hasValue:${viewNode.autofillValue != null} valueLen:${viewNode.autofillValue?.toString()?.length ?: 0} className:${viewNode.className} inputType:${viewNode.inputType} " }

        if (viewNode.autofillHints?.isNotEmpty() == true) {
            // If the client app provides autofill hints, you can obtain them using:
            logger.trace { "$depth     autofillHints: ${viewNode.autofillHints?.contentToString()}" }
        } else {
            // Or use your own heuristics to describe the contents of a view
            // using methods such as getText() or getHint().
            logger.trace { "$depth     viewNode no hints, className:${viewNode.className} hint:${viewNode.hint} inputType:${viewNode.inputType}" }
        }

        viewNode.idPackage?.let { idPackage ->
            logger.trace { "Package ID found: $idPackage" }
            nodeIdPackages.add(idPackage)
        }
        val ownDomain = viewNode.webDomain?.takeIf { it.isNotEmpty() }
        val webDomain = ownDomain ?: inheritedDomain
        val webScheme = if (ownDomain != null) viewNode.webScheme else inheritedScheme
        if (ownDomain != null) {
            val host = ownDomain.lowercase()
            val existing = claimedDomains[host]
            if (existing == null || (existing.scheme == null && webScheme != null)) {
                claimedDomains[host] = WebDomain(webScheme, ownDomain)
            }
        }
        val visible = ancestorsVisible && viewNode.visibility == View.VISIBLE
        // A field that is not displayed, or is too small to see, is where a hostile page or app
        // would hide a field to collect credentials without the user noticing.
        val fieldVisible = visible && viewNode.width > 1 && viewNode.height > 1
        viewNode.autofillId?.let { autofillId ->
            autoFillIds.add(autofillId)
            AutofillInputType.values().forEach { type ->
                fieldIds.getOrPut(type) { mutableListOf() }.addAll(
                        matchedFieldsFromHeuristics(type, viewNode, autofillId, webDomain?.lowercase(), fieldVisible)
                )
            }
        }
        if (viewNode.isFocused) focusedAutofillId = viewNode.autofillId

        val children: List<ViewNode> =
                viewNode.run {
                    (0 until childCount).map { getChildAt(it) }
                }

        children.forEach { childNode: ViewNode ->
            traverseNode(childNode, "    ", webDomain, webScheme, visible)
        }
    }

    private fun matchedFieldsFromHeuristics(type: AutofillInputType, viewNode: ViewNode, autofillId: AutofillId, webHost: String?, isVisible: Boolean): List<MatchedField> {
        val filtered = type.heuristics

                // filtering here means we have a fieldId entry but with an empty list of MatchedFields
                .filter { viewNode.autofillType != View.AUTOFILL_TYPE_NONE }

                // Include only those heuristics whose predicate matches this view node
                .filter { it.predicate(viewNode, viewNode) }

                //TODO: We can now maybe skip the weight ordering when processing the
                // list of fieldIds later?
                // We order by weight and block all heuristics from the result once a
                // heuristic with a block marker is found. In practice this will probably
                // be the very first marker since we will set a high weight for intentional
                // block operations but in future we could feasibly introduce some prioritised
                // block operations that only block a field if no higher priority matches
                // are found.
                .sortedByDescending { it.weight }

        val mapped = filtered
                .takeWhile { !it.block }
                .map { MatchedField(it, autofillId, webHost, isVisible) }
        logger.trace { "Filtered ${type.heuristics.count()} heuristics into ${filtered.count()} and extracted ${mapped.count()} MatchedFields after considering blocking heuristics" }
        return mapped
    }

    fun findNodeByAutofillId(id: AutofillId?): ViewNode? {
        return allNodes.firstOrNull { it.autofillId == id }
    }

    /**
     * Checks if there's an email field that appears to be part of a sign-in form.
     * This is used to support multi-step login flows where the email is entered first
     * and the password field appears on a subsequent screen.
     *
     * A sign-in email field is identified by:
     * 1. Being detected as an Email field type by our heuristics
     * 2. Having indicators that suggest it's for authentication, not data collection:
     *    - Chrome's ua-autofill-hints or computed-autofill-hints containing EMAIL_ADDRESS
     *    - Standard autofill hints like emailAddress
     *    - Being in a form context that doesn't have contact-form indicators
     *
     * Contact forms are excluded by checking for the presence of fields that typically
     * appear in contact/feedback forms but not in sign-in forms (e.g., message, subject fields).
     */
    fun hasSignInEmailOrUsernameField(): Boolean {
        val emailFields = fieldIds[AutofillInputType.Email]
        val usernameFields = fieldIds[AutofillInputType.UserName]
        val combinedFields = (emailFields ?: emptyList()) + (usernameFields ?: emptyList())
        if (combinedFields.isNullOrEmpty()) {
            logger.debug { "hasSignInEmailOrUsernameField: No email or username fields detected" }
            return false
        }

        // Check if any email field has sign-in indicators
        for (matchedField in combinedFields) {
            val node = findNodeByAutofillId(matchedField.autofillId)
            if (node != null && isSignInEmailOrUsernameNode(node)) {
                logger.debug { "hasSignInEmailOrUsernameField: Found sign-in email/username field with autofillId ${matchedField.autofillId}" }
                return true
            }
        }

        logger.debug { "hasSignInEmailOrUsernameField: Email/username fields found but none appear to be sign-in fields" }
        return false
    }

    /**
     * Assumes node is a user/email field and we must determine if it was mismatched due to confusion with a contact/messaging form
     */
    private fun isSignInEmailOrUsernameNode(node: ViewNode): Boolean {

        // Check if this looks like a contact form by examining all other fields
        // Contact forms typically have message/body/subject fields which sign-in forms don't have
        if (hasContactFormIndicators()) {
            logger.trace { "isSignInEmailOrUsernameNode: Contact form indicators found, not treating as sign-in" }
            return false
        }

        // If we have an email field with no contact form indicators, and it's the only
        // significant input field (or paired with a username field), treat it as sign-in
        val significantFields = countSignificantInputFields()
        if (significantFields <= 2) {
            logger.trace { "isSignInEmailOrUsernameNode: Only $significantFields significant fields, treating as potential sign-in" }
            return true
        }

        return false
    }

    /**
     * Checks if the form appears to be a contact/feedback form based on field indicators.
     */
    private fun hasContactFormIndicators(): Boolean {
        val contactFormKeywords = listOf(
            "message", "comment", "feedback", "inquiry", "question",
            "subject", "topic", "reason", "description", "query",
            "your message", "your question", "how can we help"
        )

        for (node in allNodes) {
            // Check hint text
            val hint = node.hint?.lowercase()
            if (hint != null && contactFormKeywords.any { hint == it }) {
                logger.trace { "hasContactFormIndicators: Found contact form keyword in hint: $hint" }
                return true
            }

            // Check HTML attributes for textarea or message-like fields
            val htmlInfo = node.htmlInfo
            if (htmlInfo != null) {
                // Textarea elements are strong indicators of contact forms
                if (htmlInfo.tag?.lowercase() == "textarea") {
                    logger.trace { "hasContactFormIndicators: Found textarea element" }
                    return true
                }

                val attributes = htmlInfo.attributes
                if (attributes != null) {
                    for (attr in attributes) {
                        val attrName = attr.first?.lowercase() ?: continue
                        val attrValue = attr.second?.lowercase() ?: continue

                        if ((attrName == "name" || attrName == "id" || attrName == "placeholder") &&
                            contactFormKeywords.any { attrValue == it }) {
                            logger.trace { "hasContactFormIndicators: Found contact form keyword in $attrName=$attrValue" }
                            return true
                        }
                    }
                }
            }

            // Check idEntry
            val idEntry = node.idEntry?.lowercase()
            if (idEntry != null && contactFormKeywords.any { idEntry == it }) {
                logger.trace { "hasContactFormIndicators: Found contact form keyword in idEntry: $idEntry" }
                return true
            }
        }

        return false
    }

    /**
     * Counts the number of significant input fields (text inputs that aren't hidden or buttons).
       This is a big underestimate but should prevent some false positive matches. Since we only
       consider this in the absence of any password field, any false negatives are less critical 
       but we can refine as more specific examples of problem apps/sites are revealed.
     */
    private fun countSignificantInputFields(): Int {
        return allNodes.count { node ->
            node.autofillType == View.AUTOFILL_TYPE_TEXT &&
            node.className?.contains("EditText", ignoreCase = true) == true ||
            (node.htmlInfo?.tag?.lowercase() == "input" &&
             node.htmlInfo?.attributes?.none { it.first?.lowercase() == "type" && it.second?.lowercase() in listOf("hidden", "submit", "button", "reset") } == true)
        }
    }

    override fun toString(): String {
        return "AssistStructureParser(autoFillIds=$autoFillIds, appId=$appId, appIdSource=$appIdSource, componentPackage=$componentPackage, nodeIdPackages=$nodeIdPackages, titlePackages=$titlePackages, webDomains=$webDomains, isWebOrigin=$isWebOrigin, fieldIds=$fieldIds)"
    }


}