package com.keevault.flutter_autofill_service

import android.annotation.SuppressLint
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
import android.service.autofill.FillRequest.FLAG_COMPATIBILITY_MODE_REQUEST
import android.service.autofill.FillRequest.FLAG_MANUAL_REQUEST
import android.service.autofill.FillRequest.FLAG_SUPPORTS_FILL_DIALOG
import android.view.View
import android.view.View.AUTOFILL_TYPE_TEXT
import android.view.autofill.AutofillId
import com.keevault.flutter_autofill_service.SaveHelper.createSaveInfo
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import io.github.oshai.kotlinlogging.KotlinLogging
import org.tinylog.Level
import org.tinylog.policies.DynamicPolicy
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
        autofillPreferenceStore = AutofillPreferenceStore.getInstance(applicationContext)
        System.setProperty("logs.folder", filesDir.absolutePath + "/logs");
        DynamicLevelLoggingProvider.setActiveLevel(if (autofillPreferenceStore.autofillPreferences.enableDebug) Level.TRACE else Level.OFF)
        logger.debug { "Autofill service was created. debug: ${autofillPreferenceStore.autofillPreferences.enableDebug}" }
    }

    override fun onConnected() {
        super.onConnected()

        // Make sure we have the latest log level configuration each time we are asked to start the autofill procedure.
        // Android may re-use the same process for a long time and we may have had the shared preferences updated from
        // a different process/task in the mean time.
        DynamicLevelLoggingProvider.setActiveLevel(if (autofillPreferenceStore.autofillPreferences.enableDebug) Level.TRACE else Level.OFF)

        // If the user has just deleted the autofill logs through the main app, the file handle
        // already open from this process will remain the active destination for new log entries
        // until something causes it to roll over to a new file. This call to tinylog forces the
        // file to roll at this point, so we will generally have one log file per autofill
        // operation and we'll therefore be unlikely to ever hit the 1MB file size limit,
        // although we enable that too just in case. Thus maximum space taken up by log files
        // (per the config in the example project) is going to be 30MB but in reality it will be
        // much less.
        DynamicPolicy.setReset()

        logger.debug { "onConnected." }
        val self = ComponentName(this, javaClass)

        val metaData = packageManager.getServiceInfo(self, PackageManager.GET_META_DATA).metaData
        metaData.getString("com.keevault.flutter_autofill_service.unlock_label")?.let {
            unlockLabel = it
        }
        metaData.getString("com.keevault.flutter_autofill_service.unlock_drawable_name")?.let {
            unlockDrawableId = getDrawable(it)
        }
        //TODO: Find a way to localise this message and the "pick another" message
        logger.info { "Unlock label will be $unlockLabel" }
    }

    private fun getDrawable(name: String): Int {
        val resources: Resources = resources
        return resources.getIdentifier(
            name, "drawable",
            packageName
        )
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        logger.info { "Got fill request $request" }

        val context = request.fillContexts.last()
        val parser = AssistStructureParser(context.structure, this)
        logger.debug { "DAL: isWebOrigin=${parser.isWebOrigin}, dalVerified=${parser.dalVerified}" }

        val autoFillIds = parser.autoFillIds.distinct()
        var useLabel = unlockLabel
        val clientState = Bundle()
        val compatModeRequested = request.flags.hasFlag(FLAG_COMPATIBILITY_MODE_REQUEST)
        val manuallyRequested = request.flags.hasFlag(FLAG_MANUAL_REQUEST)
        var fillDialogRequested =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) request.flags.hasFlag(
                FLAG_SUPPORTS_FILL_DIALOG
            ) else false

        val offerToSave = autofillPreferenceStore.autofillPreferences.enableSaving
        val enableIMERequests = autofillPreferenceStore.autofillPreferences.enableIMERequests

        logger.debug { "Fill request config: compatMode: $compatModeRequested , manuallyRequested: $manuallyRequested, fillDialogRequested: $fillDialogRequested, offerToSave: $offerToSave" }

        // fill dialog is another undocumented and buggy Android autofill "feature" that is
        // unacceptable for human use, at least as of late 2023.
        // Best guess is that it is only designed to be used by password managers which do not
        // protect their stored credentials behind any authentication, or perhaps only behind some
        // sort of authentication that can occur in the background, such as a privileged
        // Google-owned service.
        // Thus we keep it disabled now but retain the code that might one-day enable it if Android
        // gets fixed.
        fillDialogRequested = false

        clientState.putBoolean("isCompatMode", compatModeRequested)
        clientState.putBoolean("isManualRequest", manuallyRequested)
        clientState.putBoolean("isFillDialogRequest", fillDialogRequested)

        // Snapshot the flow for this request so a preference change before the result arrives
        // can not produce a mixed-flow response. The locked dataset needs at least one field id,
        // so the new flow is only chosen if it can be built; otherwise this request uses the
        // legacy flow, and neither the clientState flag nor the intent mode is set for the new one.
        val prefs = autofillPreferenceStore.autofillPreferences
        val lockedFieldIds = parser.fieldIds.flatMap { it.value }.map { it.autofillId }.distinct()
            .ifEmpty { autoFillIds }
        val inAppSelection = prefs.useInAppSelection && lockedFieldIds.isNotEmpty()
        if (prefs.useInAppSelection && !inAppSelection) {
            logger.warn { "No fields available for the unlock dataset; using response-level authentication." }
        }
        if (inAppSelection) {
            clientState.putBoolean("isInAppSelection", true)
            clientState.putBoolean("isEphemeralDataset", prefs.useEphemeralDataset)
        }

        val fillResponseBuilder: FillResponse.Builder = FillResponse.Builder()
            .setClientState(clientState)

        var saveInfoWasSet = false;
        if (offerToSave) {
            val (autoFillIdUsernameGuessed, autoFillIdPasswordGuessed) = SaveHelper.guessAutofillIdsForSave(
                parser
            )
            val saveInfo = createSaveInfo(
                clientState,
                autoFillIdUsernameGuessed,
                autoFillIdPasswordGuessed,
                null,
                null
            )
            saveInfo?.let {
                fillResponseBuilder.setSaveInfo(it)
                saveInfoWasSet = true;
            }
        }

        // Do not launch main Flutter app if no password fields are found,
        // unless user manually forced a fill to a non-password form,
        // or there's a sign-in email field (multi-step login where password comes later)
        if (parser.fieldIds[AutofillInputType.Password].isNullOrEmpty() && !manuallyRequested) {
            val hasSignInEmailField = parser.hasSignInEmailOrUsernameField()
            if (!hasSignInEmailField) {
                val detectedFields = parser.fieldIds.flatMap { it.value }.size
                logger.info { "Debug: No password fields detected ($detectedFields total). Non-manual request and no sign-in email field so aborting." }
                val response = if (saveInfoWasSet) fillResponseBuilder.build() else null
                callback.onSuccess(response)
                return
            }
            logger.info { "Debug: No password fields but sign-in email field detected. Proceeding with autofill for multi-step login." }
        }

        if (parser.componentPackage != null && parser.componentPackage in excludedPackages) {
            callback.onSuccess(null)
            return
        } else if (parser.nodeIdPackages.any { it in excludedPackages }) {
            callback.onSuccess(null)
            return
        } else if (parser.titlePackages.any { it in excludedPackages }) {
            callback.onSuccess(null)
            return
        }

        logger.trace { "Trying to fetch package info." }
        val activityName =
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).run {
                metaData.getString("com.keevault.flutter_autofill_service.ACTIVITY_NAME")
            } ?: "com.keevault.flutter_autofill_service_example.AutofillActivity"
        logger.debug { "got activity $activityName" }

        fun createAuthIntentSender(mode: String): IntentSender {
            val startAuthIntent = IntentHelpers.getStartIntent(
                activityName,
                parser.appId,
                parser.appIdTrusted,
                parser.webDomains,
                applicationContext,
                mode,
                null,
                parser.isWebOrigin,
                parser.dalVerified,
                parser.appIsBrowser
            )
            //startAuthIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) Can't start new task cos results will never be returned
            val pendingIntent: PendingIntent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pendingIntent = PendingIntent.getActivity(
                    this,
                    IntentHelpers.REQUEST_CODE_UNLOCK,
                    startAuthIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            } else {
                @SuppressLint("UnspecifiedImmutableFlag")
                pendingIntent = PendingIntent.getActivity(
                    this,
                    IntentHelpers.REQUEST_CODE_UNLOCK,
                    startAuthIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT
                )
            }
            logger.debug { "startIntent:$startAuthIntent (${startAuthIntent.extras}) - sender: ${pendingIntent.intentSender}" }
            return pendingIntent.intentSender
        }

        var intentSender = createAuthIntentSender(if (inAppSelection) "/autofill_select" else "/autofill")

        // The in-app selection flow authenticates a single locked dataset rather than the whole
        // response. The framework fills the dataset returned by the auth activity straight away,
        // whereas the reply to response-level authentication is only offered as suggestions.
        var lockedDataset: Dataset? = null
        if (inAppSelection) {
            lockedDataset = buildUnlockDataset(
                request, lockedFieldIds, useLabel, enableIMERequests, intentSender
            )
            if (lockedDataset == null) {
                // The flag and intent mode must not outlive the decision to use the new flow, or
                // the plugin would reply to response-level authentication with a bare Dataset.
                logger.warn { "Failed to build the unlock dataset; using response-level authentication." }
                clientState.remove("isInAppSelection")
                clientState.remove("isEphemeralDataset")
                fillResponseBuilder.setClientState(clientState)
                intentSender = createAuthIntentSender("/autofill")
            }
        }

        // Build a FillResponse object that requires authentication.
        if (lockedDataset != null) {
            fillResponseBuilder.addDataset(lockedDataset)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val inlineRequest = if (enableIMERequests) {
                request.inlineSuggestionsRequest
            } else {
                null
            }
            val respondInline = (inlineRequest?.maxSuggestionCount ?: 0) >= 2
            val presentationMenu =
                RemoteViewsHelper.viewsWithAuth(packageName, useLabel, unlockDrawableId)
            val presentationInline = if (respondInline) InlinePresentationHelper.viewsWithAuth(
                useLabel,
                inlineRequest!!.inlinePresentationSpecs.first(),
                null,
                this,
                unlockDrawableId,
                false
            ) else null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val presentationDialog = if (fillDialogRequested) presentationMenu else null
                val presentationBuilder =
                    Presentations.Builder().setMenuPresentation(presentationMenu)
                if (presentationInline != null) presentationBuilder.setInlinePresentation(
                    presentationInline
                )
                if (presentationDialog != null) {
                    presentationBuilder.setDialogPresentation(presentationDialog)
                    // In theory we should be able to set all the autofill ids here so when the user
                    // taps on any one of them they instantly see the fancy new dialog UI. However,
                    // at least in every example app I have found so far, it just makes the dialog
                    // appear when the app first loads. Additionally, there doesn't seem to be a way
                    // to enable an authentication step here so the user has to manually click on a
                    // field again and still click on a dataset result so overall it is far worse than
                    // the v1 autofill feature.
                    // fillResponseBuilder.setFillDialogTriggerIds(*autoFillIds.toTypedArray())
                }
                val presentations = presentationBuilder.build()
                fillResponseBuilder.setAuthentication(
                    autoFillIds.toTypedArray(),
                    intentSender,
                    presentations
                )
            } else {
                fillResponseBuilder.setAuthentication(
                    autoFillIds.toTypedArray(),
                    intentSender,
                    presentationMenu,
                )
            }
        } else {
            fillResponseBuilder.setAuthentication(
                autoFillIds.toTypedArray(),
                intentSender,
                RemoteViewsHelper.viewsWithAuth(packageName, useLabel, unlockDrawableId),
            )
        }
        logger.info {
            "remoteView for packageName: $packageName -- " +
                    "detected autofill appId: ${parser.appId} (trusted:${parser.appIdTrusted}) " +
                    "webDomains: ${parser.webDomains}" +
                    "autoFillIds: ${autoFillIds.size}"
        }

        try {
            callback.onSuccess(fillResponseBuilder.build())
        } catch (e: TransactionTooLargeException) {
            logger.error({ "Too many auto fill ids discovered ${autoFillIds.size} for ${parser.webDomains},  ${parser.appId} (trusted:${parser.appIdTrusted})" })
            throw RuntimeException(
                "Too many auto fill ids discovered ${autoFillIds.size} for " +
                        "${parser.webDomains}, ${parser.appId}",
                e
            )
        }
    }

    private fun buildUnlockDataset(
        request: FillRequest,
        fieldIds: Collection<AutofillId>,
        label: String,
        enableIMERequests: Boolean,
        intentSender: IntentSender,
    ): Dataset? {
        val menu = RemoteViewsHelper.viewsWithAuth(packageName, label, unlockDrawableId)
        var inline: InlinePresentation? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && enableIMERequests) {
            val inlineRequest = request.inlineSuggestionsRequest
            // The unlock dataset is the only suggestion so a single slot is enough.
            if (inlineRequest != null && inlineRequest.maxSuggestionCount >= 1) {
                inline = InlinePresentationHelper.viewsWithAuth(
                    label,
                    inlineRequest.inlinePresentationSpecs.first(),
                    null,
                    this,
                    unlockDrawableId,
                    false
                )
            }
        }
        return UnlockDatasetHelper.build(fieldIds, menu, inline, intentSender)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        logger.info { "onSaveRequest." }

        logger.trace { "Trying to fetch package info." }
        val activityName =
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).run {
                metaData.getString("com.keevault.flutter_autofill_service.SAVE_ACTIVITY_NAME")
                    ?: metaData.getString("com.keevault.flutter_autofill_service.ACTIVITY_NAME")
            } ?: "com.keevault.flutter_autofill_service_example.AutofillActivity"
        logger.debug { "got activity $activityName" }

        val clientState = request.clientState!!
        val usernameId = clientState.getParcelable<AutofillId>("usernameId")
        val passwordId = clientState.getParcelable<AutofillId>("passwordId")
        var username: String? = null
        var password: String? = null
        var appId: String? = null
        var appIdTrusted: Boolean = false
        var webDomains: Set<WebDomain>? = null
        var isWebOrigin: Boolean = false
        var dalVerified: Boolean = false
        var appIsBrowser: Boolean = false

        request.fillContexts.reversed().forEach { context ->
            val parser = AssistStructureParser(context.structure, this)
            logger.debug { "DAL: isWebOrigin=${parser.isWebOrigin}, dalVerified=${parser.dalVerified}" }
            val usernameNode = parser.findNodeByAutofillId(usernameId)
            val passwordNode = parser.findNodeByAutofillId(passwordId)
            try {
                if (username == null) {
                    usernameNode?.let { username = it.autofillValue?.textValue.toString() }
                }
            } catch (ex: IllegalStateException) {
                logger.warn { "username autofill value was not text." }
            }
            try {
                if (password == null) {
                    passwordNode?.let { password = it.autofillValue?.textValue.toString() }
                }
            } catch (ex: IllegalStateException) {
                logger.warn { "password autofill value was not text." }
            }
            // The first fill context that contains an appId or set of web domains becomes our
            // consistent picture of whether they are trustworthy or not
            if (appId == null && parser.appId != null) {
                appId = parser.appId
                appIdTrusted = parser.appIdTrusted
                appIsBrowser = parser.appIsBrowser
            }
            if (webDomains == null && parser.webDomains.isNotEmpty()) {
                webDomains = parser.webDomains
                isWebOrigin = parser.isWebOrigin
                dalVerified = parser.dalVerified
            }
        }
        val isCompatMode =
            if (clientState.containsKey("isCompatMode")) clientState.getBoolean("isCompatMode") else null

        val startIntent = IntentHelpers.getStartIntent(
            activityName,
            appId,
            appIdTrusted,
            webDomains ?: setOf(),
            applicationContext,
            "/autofill_save",
            SaveInfoMetadata(username, password, isCompatMode),
            isWebOrigin,
            dalVerified,
            appIsBrowser
        )
        startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(startIntent)

        // This apparently does nothing but Android docs say it is required.
        callback.onSuccess()
    }
}

@JsonClass(generateAdapter = true)
data class AutofillMetadata(
        val appId: String? = null,
        val appIdTrusted: Boolean = false,
        val webDomains: Set<WebDomain>,
        val isWebOrigin: Boolean = false,
        val dalVerified: Boolean = false,
        val appIsBrowser: Boolean = false,
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
        val password: String?,
        val isCompatMode: Boolean?
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
        "user-name",
        "user",
        "uname",
        "email",
        "e-mail",
        "emailaddress",
        "email-address",
        "email_address",
        "mail",
        "login",
        "user id",
)

private val passwordHints = listOf(
        "password",
        "pass",
        "passwort",
        "pswd",
        "passwordAuto",
)

private val blockHints = listOf(
        "search",
        "find",
        "filter",
        "recipient",
)

private val otpHints = listOf(
    "totp",
    "2fa",
    "mfa",
    "code",
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

private fun MutableList<AutofillHeuristic>.autofillHint(weight: Int, hint: String, block: Boolean? = false) =
        heuristic(weight, "aHint", block) { autofillHints?.contains(hint) == true }

private fun MutableList<AutofillHeuristic>.nonAutofillHint(weight: Int, matches: List<String>, block: Boolean? = false) =
        heuristic(weight, "naHint", block) { hint?.lowercase()?.let { h -> matches.any { it in h} } ?: false }

private fun MutableList<AutofillHeuristic>.idEntryExact(weight: Int, match: String, block: Boolean? = false) =
        heuristic(weight, "id=$match", block) { idEntry == match }

private fun MutableList<AutofillHeuristic>.idEntry(weight: Int, matches: List<String>, block: Boolean? = false) =
        heuristic(weight, "id", block) { idEntry?.lowercase()?.let { i -> matches.any { it in i} } ?: false }

private fun MutableList<AutofillHeuristic>.htmlAttribute(weight: Int, attr: String, value: String) =
        heuristic(weight, "html[$attr=$value]") { htmlInfo?.attributes?.firstOrNull { it.first?.lowercase() == attr && it.second?.lowercase() == value } != null }

private fun MutableList<AutofillHeuristic>.contentDescription(weight: Int, matches: List<String>, block: Boolean? = false) =
    heuristic(weight, "contentDescription", block) { contentDescription?.toString()?.lowercase()?.let { cd -> matches.contains(cd) } ?: false }

private fun MutableList<AutofillHeuristic>.defaults(hint: String, match: String) {
    // Could consider support for AUTOFILL_TYPE_DATE or AUTOFILL_TYPE_LIST in future if these are
    // ever used for things like Date of Birth or a pick-one-char-from-password dropdown but will
    // need to see real world examples of actual usage first.
    heuristic(10000, "not text", true) { autofillType != AUTOFILL_TYPE_TEXT}
    autofillHint(10000, "notApplicable", true)
    nonAutofillHint(10000, blockHints, true)
    idEntry(10000, blockHints, true)
    autofillHint(900, hint)
    idEntryExact(800, match)
    idEntry(700, listOf(match))
}

private fun MutableList<AutofillHeuristic>.htmlHint(weight: Int, attrName: String, matches: List<String>, block: Boolean? = false) {
    heuristic(weight, "htmlHints $attrName", block) { htmlInfo?.attributes
        ?.any {  it.first?.lowercase() == attrName && matches.any { m -> it.second?.lowercase() == m
        } } ?: false }
}

private fun MutableList<AutofillHeuristic>.htmlHints(matches: List<String>, block: Boolean? = false) {
    htmlHint(1000, "ua-autofill-hints", matches, block)
    htmlHint(900, "computed-autofill-hints", matches, block)
    htmlHint(800, "crowdsourcing-autofill-hints", matches, block)
    htmlHint(700, "crowdsourcing-predictions-autofill-hints", matches, block)
}

enum class AutofillInputType(val heuristics: List<AutofillHeuristic>) {
    Password(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_PASSWORD, "password")
        autofillHint(10000, "newPassword", true)
        autofillHint(10000, "newUsername", true)
        htmlHints(listOf("pass", "password"))
        htmlAttribute(400, "type", "password")
        heuristic(240, "text variation password") { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) }
        heuristic(239, "text variation web password") {  inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) }
        heuristic(238, "text variation visible password") { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) }
        nonAutofillHint(200, passwordHints)
        contentDescription(300, passwordHints)
    }),
    Email(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_EMAIL_ADDRESS, "mail")
        autofillHint(10000, "newPassword", true)
        autofillHint(10000, "newUsername", true)
        htmlHints(listOf("email", "email-address", "email_address"))
        htmlAttribute(400, "type", "mail")
        htmlAttribute(300, "name", "mail")
        heuristic(250, "hint=mail") { hint?.lowercase(Locale.ROOT)?.contains("mail") == true }
        heuristic(240, "text variation email") { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) }
        heuristic(239, "text variation web email") { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) }
        //TODO: In future might want to consider Email enum value both an existing and new username match?
    }),
    UserName(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_USERNAME, "user")
        autofillHint(10000, "newPassword", true)
        autofillHint(10000, "newUsername", true)
        htmlHints(listOf("user", "username"))
        htmlAttribute(400, "name", "user")
        htmlAttribute(400, "name", "username")
        nonAutofillHint(300, usernameHints)
        contentDescription(300, usernameHints)
    }),
    TOTP(mutableListOf<AutofillHeuristic>().apply {
        defaults("2faAppOTPCode", "totp")
        autofillHint(10000, "newPassword", true)
        autofillHint(10000, "newUsername", true)
        htmlAttribute(400, "name", "totp")
        htmlAttribute(400, "name", "code")
        nonAutofillHint(300, otpHints)
    }),
    NewUserName(mutableListOf<AutofillHeuristic>().apply {
        defaults("newUsername", "user")
        autofillHint(10000, "username", true)
        htmlAttribute(400, "name", "user")
        htmlAttribute(400, "name", "username")
        nonAutofillHint(300, usernameHints)
        contentDescription(300, usernameHints)
    }),
    NewPassword(mutableListOf<AutofillHeuristic>().apply {
        defaults("newPassword", "password")
        autofillHint(10000, "password", true)
        htmlAttribute(400, "type", "password")
        heuristic(240, "text variation password") { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) }
        heuristic(239, "text variation web password") {  inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) }
        heuristic(238, "text variation visible password") { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) }
        nonAutofillHint(200, passwordHints)
        contentDescription(300, passwordHints)
    }),
}

inline fun Int?.hasFlag(flag: Int) = this != null && flag and this == flag
inline fun Int.withFlag(flag: Int) = this or flag
inline fun Int.minusFlag(flag: Int) = this and flag.inv()

data class MatchedField(val heuristic: AutofillHeuristic, val autofillId: AutofillId)
