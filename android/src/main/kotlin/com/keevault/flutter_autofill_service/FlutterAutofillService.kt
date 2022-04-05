package com.keevault.flutter_autofill_service

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.TransactionTooLargeException
import android.service.autofill.*
import android.view.View
import android.view.autofill.AutofillId
import com.keevault.flutter_autofill_service.SaveHelper.createSaveInfo
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import mu.KotlinLogging
import java.util.*


private val logger = KotlinLogging.logger {}

class FlutterAutofillService : AutofillService() {

    private val excludedPackages = listOf(
            "com.keevault.keevault",
            "android",
            "com.android.settings",
            "com.oneplus.applocker",
    )

    private lateinit var autofillPreferenceStore: AutofillPreferenceStore
    private var unlockLabel = "Autofill"
    private var unlockDrawableId = R.drawable.ic_lock_24dp

    override fun onCreate() {
        super.onCreate()
        logger.debug { "Autofill service was created." }
        autofillPreferenceStore = AutofillPreferenceStore.getInstance(applicationContext)
    }

    override fun onConnected() {
        super.onConnected()
        logger.debug("onConnected.")
        val self = ComponentName(this, javaClass)

        val metaData = packageManager.getServiceInfo(self, PackageManager.GET_META_DATA).metaData
        metaData.getString("com.keevault.flutter_autofill_service.unlock_label")?.let {
            unlockLabel = it
        }
        metaData.getString("com.keevault.flutter_autofill_service.unlock_drawable_name")?.let {
            unlockDrawableId = getDrawable(it)
        }
        //TODO: Find a way to localise this message and the "pick another" message
        logger.info("Unlock label will be $unlockLabel")
    }

    private fun getDrawable(name: String): Int {
        val resources: Resources = resources
        return resources.getIdentifier(name, "drawable",
                packageName)
    }

    override fun onFillRequest(
            request: FillRequest,
            cancellationSignal: CancellationSignal,
            callback: FillCallback
    ) {
        logger.info { "Got fill request $request" }

        val context = request.fillContexts.last()
        val parser = AssistStructureParser(context.structure)

        val autoFillIds = parser.autoFillIds.distinct()
        var useLabel = unlockLabel
        val clientState = Bundle()

        val fillResponseBuilder: FillResponse.Builder = FillResponse.Builder()
                .setClientState(clientState)

        val offerToSave = autofillPreferenceStore.autofillPreferences.enableSaving

        if (offerToSave) {
        val (autoFillIdUsernameGuessed, autoFillIdPasswordGuessed) = SaveHelper.guessAutofillIdsForSave(parser, ArrayList(autoFillIds))
        val saveInfo = createSaveInfo(clientState, autoFillIdUsernameGuessed, autoFillIdPasswordGuessed, null, null)
        saveInfo?.let {fillResponseBuilder.setSaveInfo(it)}
        }

        // Do not launch Kee Vault if no password fields are found, unless a temporary flag is
        // enabled to aid debugging why no such field was detected.
        if (parser.fieldIds[AutofillInputType.Password].isNullOrEmpty()) {
            val detectedFields = parser.fieldIds.flatMap { it.value }.size
            if (!autofillPreferenceStore.autofillPreferences.enableDebug) {
                callback.onSuccess(fillResponseBuilder.build())
                return
            }
            useLabel = "Debug: No password fields detected ($detectedFields total)."
        }

        if (parser.packageNames.any { it in excludedPackages }) {
            callback.onSuccess(null)
            return
        }

        logger.debug { "Trying to fetch package info." }
        val activityName = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).run {
            metaData.getString("com.keevault.flutter_autofill_service.ACTIVITY_NAME")
        } ?: "com.keevault.flutter_autofill_service_example.AutofillActivity"
        logger.debug("got activity $activityName")

        val startAuthIntent = IntentHelpers.getStartIntent(activityName, parser.packageNames, parser.webDomains, applicationContext, "/autofill", null)
        //startAuthIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) Can't start new task cos results will never be returned
        val pendingIntent: PendingIntent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingIntent = PendingIntent.getActivity(
                    this,
                    1230,
                    startAuthIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        } else {
            @SuppressLint("UnspecifiedImmutableFlag")
            pendingIntent = PendingIntent.getActivity(
                    this,
                    1230,
                    startAuthIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT
            )
        }
        val intentSender: IntentSender = pendingIntent.intentSender

        logger.debug { "startIntent:$startAuthIntent (${startAuthIntent.extras}) - sender: $intentSender" }

        // Build a FillResponse object that requires authentication.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val inlineRequest = request.inlineSuggestionsRequest
//TODO: Enable respond inline here if we choose to support it in future
            val respondInline = false
//        val respondInline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            inlineRequest?.maxSuggestionCount ?: 0 > 0
//        } else false
            fillResponseBuilder.setAuthentication(
                    autoFillIds.toTypedArray(),
                    intentSender,
                    if (!respondInline) RemoteViewsHelper.viewsWithAuth(packageName, useLabel, unlockDrawableId) else null,
                    //null
                    if (respondInline) InlinePresentationHelper.viewsWithAuth(useLabel, inlineRequest!!.inlinePresentationSpecs.first(), pendingIntent, this) else null
            )
        } else {
            fillResponseBuilder.setAuthentication(
                    autoFillIds.toTypedArray(),
                    intentSender,
                    RemoteViewsHelper.viewsWithAuth(packageName, useLabel, unlockDrawableId),
            )

        }
        logger.info {
            "remoteView for packageName: $packageName -- " +
                    "detected autofill packageNames: ${parser.packageNames} " +
                    "webDomains: ${parser.webDomains}" +
                    "autoFillIds: ${autoFillIds.size}"
        }

        try {
            callback.onSuccess(fillResponseBuilder.build())
        } catch (e: TransactionTooLargeException) {
            throw RuntimeException(
                    "Too many auto fill ids discovered ${autoFillIds.size} for " +
                            "${parser.webDomains},  ${parser.packageNames}",
                    e
            )
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        logger.info { "onSaveRequest." }

        logger.debug { "Trying to fetch package info." }
        val activityName = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).run {
            metaData.getString("com.keevault.flutter_autofill_service.SAVE_ACTIVITY_NAME")
                    ?: metaData.getString("com.keevault.flutter_autofill_service.ACTIVITY_NAME")
        } ?: "com.keevault.flutter_autofill_service_example.AutofillActivity"
        logger.debug("got activity $activityName")

        val clientState = request.clientState!!
        val usernameId = clientState.getParcelable<AutofillId>("usernameId")
        val passwordId = clientState.getParcelable<AutofillId>("passwordId")
        var username: String? = null
        var password: String? = null
        var packageNames: Set<String>? = null
        var webDomains: Set<WebDomain>? = null

        request.fillContexts.reversed().forEach { context ->
            val parser = AssistStructureParser(context.structure)
            val usernameNode = parser.findNodeByAutofillId(usernameId)
            val passwordNode = parser.findNodeByAutofillId(passwordId)
            if (username == null) {
                usernameNode?.let { username = it.autofillValue?.textValue.toString() }
            }
            if (password == null) {
                passwordNode?.let { password = it.autofillValue?.textValue.toString() }
            }
            if (packageNames == null) {
                packageNames = parser.packageNames
            }
            if (webDomains == null) {
                webDomains = parser.webDomains
            }
        }

        val startIntent = IntentHelpers.getStartIntent(activityName, packageNames
                ?: setOf(), webDomains
                ?: setOf(), applicationContext, "/autofill_save", SaveInfoMetadata(username, password))
        startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(startIntent)

        // This apparently does nothing but Android docs say it is required.
        callback.onSuccess()
    }
}

@JsonClass(generateAdapter = true)
data class AutofillMetadata(
        val packageNames: Set<String>,
        val webDomains: Set<WebDomain>,
        val saveInfo: SaveInfoMetadata?
) {
    companion object {
        const val EXTRA_NAME = "AutofillMetadata"

        private val moshi = Moshi.Builder()
                .build() as Moshi
        private val jsonAdapter
            get() =
                requireNotNull(moshi.adapter(AutofillMetadata::class.java))

        fun fromJsonString(json: String) =
                requireNotNull(jsonAdapter.fromJson(json))
    }

    fun toJson(): Any? = jsonAdapter.toJsonValue(this)
    fun toJsonString(): String = jsonAdapter.toJson(this)
}

@JsonClass(generateAdapter = true)
data class SaveInfoMetadata(
        val username: String?,
        val password: String?
) {
    companion object {
        const val EXTRA_NAME = "SaveInfoMetadata"

        private val moshi = Moshi.Builder()
                .build() as Moshi
        private val jsonAdapter
            get() =
                requireNotNull(moshi.adapter(SaveInfoMetadata::class.java))

        fun fromJsonString(json: String) =
                requireNotNull(jsonAdapter.fromJson(json))
    }

    fun toJson(): Any? = jsonAdapter.toJsonValue(this)
    fun toJsonString(): String = jsonAdapter.toJson(this)
}

private val usernameHints = listOf(
        "username",
        "e-mail",
        "mail",
        "login",
)

private val passwordHints = listOf(
        "password",
        "passwort",
        "pswd",
)

private val blockHints = listOf(
        "search",
        "find",
        "recipient",
)

data class AutofillHeuristic(
        val weight: Int,
        val message: String?,
        val block: Boolean,
        val predicate: AssistStructure.ViewNode.(node: AssistStructure.ViewNode) -> Boolean
)

private fun MutableList<AutofillHeuristic>.heuristic(
        weight: Int,
        message: String? = null,
        block: Boolean? = false,
        predicate: AssistStructure.ViewNode.(node: AssistStructure.ViewNode) -> Boolean
) =
        add(AutofillHeuristic(weight, message, block ?: false, predicate))


@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.autofillHint(weight: Int, hint: String) =
        heuristic(weight) { autofillHints?.contains(hint) == true }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.nonAutofillHint(weight: Int, matches: List<String>, block: Boolean? = false) =
        heuristic(weight, "naHint", block) { hint?.lowercase()?.let { h -> matches.any { it in h} } ?: false }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.idEntry(weight: Int, match: String, block: Boolean? = false) =
        heuristic(weight, "id=$match", block) { idEntry == match }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.idEntry(weight: Int, matches: List<String>, block: Boolean? = false) =
        heuristic(weight, "id", block) { idEntry?.lowercase()?.let { i -> matches.any { it in i} } ?: false }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.htmlAttribute(weight: Int, attr: String, value: String) =
        heuristic(weight, "html[$attr=$value]") { htmlInfo?.attributes?.firstOrNull { it.first == attr && it.second == value } != null }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.defaults(hint: String, match: String) {
    autofillHint(900, hint)
    idEntry(800, match)
    heuristic(700) { idEntry?.lowercase(Locale.ROOT)?.contains(match) == true }
}

@TargetApi(Build.VERSION_CODES.O)
enum class AutofillInputType(val heuristics: List<AutofillHeuristic>) {
    Password(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_PASSWORD, "password")
        htmlAttribute(400, "type", "password")
        heuristic(240, "text variation password") { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) }
        heuristic(239, "text variation web password") {  inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) }
        heuristic(238, "text variation visible password") { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) }
        nonAutofillHint(200, passwordHints)
        nonAutofillHint(10000, blockHints, true)
        idEntry(10000, blockHints, true)
    }),
    Email(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_EMAIL_ADDRESS, "mail")
        htmlAttribute(400, "type", "mail")
        htmlAttribute(300, "name", "mail")
        heuristic(250, "hint=mail") { hint?.lowercase(Locale.ROOT)?.contains("mail") == true }
        nonAutofillHint(10000, blockHints, true)
        idEntry(10000, blockHints, true)
    }),
    UserName(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_USERNAME, "user")
        htmlAttribute(400, "name", "user")
        htmlAttribute(400, "name", "username")
        nonAutofillHint(300, usernameHints)
        nonAutofillHint(10000, blockHints, true)
        idEntry(10000, blockHints, true)
    }),
}

inline fun Int?.hasFlag(flag: Int) = this != null && flag and this == flag
inline fun Int.withFlag(flag: Int) = this or flag
inline fun Int.minusFlag(flag: Int) = this and flag.inv()

data class MatchedField(val heuristic: AutofillHeuristic, val autofillId: AutofillId)
