package com.keevault.flutter_autofill_service

import android.app.assist.AssistStructure
import android.os.*
import android.view.*
import android.view.autofill.AutofillId
import com.squareup.moshi.JsonClass
import io.github.oshai.kotlinlogging.KotlinLogging
import android.app.assist.AssistStructure.ViewNode

private val logger = KotlinLogging.logger {}

@JsonClass(generateAdapter = true)
data class WebDomain(val scheme: String?, val domain: String)

class AssistStructureParser(structure: AssistStructure) {

    val autoFillIds = mutableListOf<AutofillId>()
    val allNodes = mutableListOf<ViewNode>()

    var packageNames = HashSet<String>()
    var webDomains = HashSet<WebDomain>()

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
        val componentPkg = structure.activityComponent.packageName
        logger.debug { "component package ID: $componentPkg" }
        packageNames.add(componentPkg)
        normaliseParsedStructure()
    }

    private fun normaliseParsedStructure() {
        if (packageNames.isNotEmpty()) {
            packageNames.removeAll(excludedPackageIds)
            packageNames.removeIf() {p -> p.startsWith("PopupWindow:")}
        }
        if (webDomains.isNotEmpty() && !packageNames.any { it in trustedCompatBrowsers + trustedNativeBrowsers }) {
            webDomains.clear()
        }
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
            titlePackage?.let { packageNames.add(it) }
            windowNode.rootViewNode?.let { traverseNode(it, "") }
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
                is ViewStructure.HtmlInfo -> "HtmlInfo{<$tag ${attributes?.joinToString(" ") { "${it.first}=\"${it.second}\"" }}>}"
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

    private fun traverseNode(viewNode: ViewNode, depth: String) {
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
                        viewNode::getAutofillValue,
                        viewNode::getText,
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
        logger.trace { "$depth     We got autofillId: ${viewNode.autofillId} autofillOptions:${viewNode.autofillOptions} autofillType:${viewNode.autofillType} autofillValue:${viewNode.autofillValue} " }

        if (viewNode.autofillHints?.isNotEmpty() == true) {
            // If the client app provides autofill hints, you can obtain them using:
            logger.trace { "$depth     autofillHints: ${viewNode.autofillHints?.contentToString()}" }
        } else {
            // Or use your own heuristics to describe the contents of a view
            // using methods such as getText() or getHint().
            logger.trace { "$depth     viewNode no hints, text:${viewNode.text} and hint:${viewNode.hint} and inputType:${viewNode.inputType}" }
        }

        viewNode.idPackage?.let { idPackage ->
            logger.trace { "Package ID found: $idPackage" }
            packageNames.add(idPackage)
        }
        viewNode.webDomain?.let { webDomain ->
            if (webDomain.isNotEmpty()) {
                webDomains.add(
                        WebDomain(
                                viewNode.webScheme, webDomain
                        )
                )
            }
        }
        viewNode.autofillId?.let { autofillId ->
            autoFillIds.add(autofillId)
            AutofillInputType.values().forEach { type ->
                fieldIds.getOrPut(type) { mutableListOf() }.addAll(
                        matchedFieldsFromHeuristics(type, viewNode, autofillId)
                )
            }
        }
        if (viewNode.isFocused) focusedAutofillId = viewNode.autofillId

        val children: List<ViewNode> =
                viewNode.run {
                    (0 until childCount).map { getChildAt(it) }
                }

        children.forEach { childNode: ViewNode ->
            traverseNode(childNode, "    ")
        }
    }

    private fun matchedFieldsFromHeuristics(type: AutofillInputType, viewNode: ViewNode, autofillId: AutofillId): List<MatchedField> {
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
                .map { MatchedField(it, autofillId) }
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
        return "AssistStructureParser(autoFillIds=$autoFillIds, packageNames=$packageNames, webDomains=$webDomains, fieldIds=$fieldIds)"
    }


}