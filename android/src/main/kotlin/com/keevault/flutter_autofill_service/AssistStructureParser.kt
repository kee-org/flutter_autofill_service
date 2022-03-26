package com.keevault.flutter_autofill_service

import android.annotation.TargetApi
import android.app.assist.AssistStructure
import android.os.*
import android.view.*
import android.view.autofill.AutofillId
import androidx.annotation.RequiresApi
import com.squareup.moshi.JsonClass
import mu.KotlinLogging
import android.app.assist.AssistStructure.ViewNode

private val logger = KotlinLogging.logger {}

@JsonClass(generateAdapter = true)
data class WebDomain(val scheme: String?, val domain: String)

@RequiresApi(Build.VERSION_CODES.O)
class AssistStructureParser(structure: AssistStructure) {

    val autoFillIds = mutableListOf<AutofillId>()
    val allNodes = mutableListOf<AssistStructure.ViewNode>()

    var packageNames = HashSet<String>()
    var webDomains = HashSet<WebDomain>()

    val fieldIds =
            mutableMapOf<AutofillInputType, MutableList<MatchedField>>()

    private val excludedPackageIds: List<String> = listOf("android");


    private val trustedNativeBrowsers: List<String> = listOf(
            "com.duckduckgo.mobile.android",
            "org.mozilla.focus",
            "org.mozilla.klar",
    );

    private val trustedCompatBrowsers: List<String> = listOf(
            "acr.browser.lightning",
            "acr.browser.barebones",
            "alook.browser",
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
            "com.kiwibrowser.browser",
            "com.microsoft.emmx",
            "com.mmbox.browser",
            "com.mmbox.xbrowser",
            "com.naver.whale",
            "com.opera.browser",
            "com.opera.browser.beta",
            "com.opera.mini.native",
            "com.opera.mini.native.beta",
            "com.opera.touch",
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
    );

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

    @TargetApi(Build.VERSION_CODES.O)
    private fun traverseNode(viewNode: AssistStructure.ViewNode, depth: String) {
        allNodes.add(viewNode)
//        logger.debug { "We got autofillId: ${viewNode.autofillId} autofillOptions:${viewNode.autofillOptions} autofillType:${viewNode.autofillType} autofillValue:${viewNode.autofillValue} " }
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
                ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    listOf(
                            viewNode::getWebScheme,
                            viewNode::getTextIdEntry,
                            viewNode::getImportantForAutofill
                    )
                } else {
                    emptyList()
                })
                        .map { it.name.replaceFirst("get", "") to it.invoke()?.debugToString() }
//        logger.debug { "$depth ` ViewNode: $debug ---- ${debug.toList()}" }
        logger.debug { "$depth ` ViewNode: ${debug.filter { it.second != null }.toList()}" }
        logger.debug { "$depth     We got autofillId: ${viewNode.autofillId} autofillOptions:${viewNode.autofillOptions} autofillType:${viewNode.autofillType} autofillValue:${viewNode.autofillValue} " }
//        logger.debug { "$depth ` We got node: ${viewNode.toStringReflective()}" }

        if (viewNode.autofillHints?.isNotEmpty() == true) {
            // If the client app provides autofill hints, you can obtain them using:
            logger.debug { "$depth     autofillHints: ${viewNode.autofillHints?.contentToString()}" }
        } else {
            // Or use your own heuristics to describe the contents of a view
            // using methods such as getText() or getHint().
            logger.debug { "$depth     viewNode no hints, text:${viewNode.text} and hint:${viewNode.hint} and inputType:${viewNode.inputType}" }
        }
        logger.debug { "doing it now 2"}
        viewNode.idPackage?.let { idPackage ->
            packageNames.add(idPackage)
        }
        viewNode.webDomain?.let { webDomain ->
            if (webDomain.isNotEmpty()) {
                webDomains.add(
                        WebDomain(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    viewNode.webScheme
                                } else {
                                    null
                                }, webDomain
                        )
                )
            }
        }
        viewNode.autofillId?.let { autofillId ->
            autoFillIds.add(autofillId)
            AutofillInputType.values().forEach { type ->
                fieldIds.getOrPut(type) { mutableListOf() }.addAll(
                        type.heuristics
                                //TODO: Maybe we can filter here to remove newUsername/newPassword?
                                // But probably it will prevent the save feature from working.
                                // Perhaps instead can create a new AutoFillInputType of NewPassword
                                // and give it a slightly different set of heuristics?
                                //.filter { !(viewNode.autofillHints?.contains("newPassword") ?: false) }

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
                                .dropLastWhile { it.block }
                                .map { MatchedField(it, autofillId) }
                )
            }
        }

        val children: List<AssistStructure.ViewNode>? =
                viewNode.run {
                    (0 until childCount).map { getChildAt(it) }
                }

        children?.forEach { childNode: AssistStructure.ViewNode ->
            traverseNode(childNode, "    ")
        }
    }

    fun findNodeByAutofillId(id: AutofillId?): ViewNode? {
        return allNodes.firstOrNull { it.autofillId == id }
    }

    override fun toString(): String {
        return "AssistStructureParser(autoFillIds=$autoFillIds, packageNames=$packageNames, webDomains=$webDomains, fieldIds=$fieldIds)"
    }


}