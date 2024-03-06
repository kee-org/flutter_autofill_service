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

    private val excludedPackageIds: List<String> = listOf("android")


    private val trustedNativeBrowsers: List<String> = listOf(
            "com.duckduckgo.mobile.android",
            "org.mozilla.focus",
            "org.mozilla.klar",
    )

    private val trustedCompatBrowsers: List<String> = listOf(
            "acr.browser.lightning",
            "acr.browser.barebones",
            "alook.browser",
            "alook.browser.google",
            "com.amazon.cloud9",
            "com.android.browser",
            "com.android.chrome",
            "com.android.htmlviewer",
            "com.avast.android.secure.browser",
            "com.avg.android.secure.browser",
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.brave.browser_default",
            "com.brave.browser_dev",
            "com.brave.browser_nightly",
            "com.chrome.beta",
            "com.chrome.canary",
            "com.chrome.dev",
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
            "o.github.forkmaintainers.iceraven",
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
            "org.chromium.chrome",
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
        normaliseParsedStructure()
    }

    private fun normaliseParsedStructure() {
        if (packageNames.isNotEmpty()) {
            packageNames.removeAll(excludedPackageIds)
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
            windowNode.rootViewNode?.let { traverseNode(it, "") }
        }
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

    override fun toString(): String {
        return "AssistStructureParser(autoFillIds=$autoFillIds, packageNames=$packageNames, webDomains=$webDomains, fieldIds=$fieldIds)"
    }


}