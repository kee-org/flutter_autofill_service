package com.keevault.flutter_autofill_service

import android.R.attr.value
import android.annotation.SuppressLint
import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT
import android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET
import android.view.autofill.AutofillValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.keevault.flutter_autofill_service.InlinePresentationHelper.viewsWithNoAuth
import com.keevault.flutter_autofill_service.IntentHelpers.getStartIntent
import com.keevault.flutter_autofill_service.SaveHelper.createSaveInfo
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.github.oshai.kotlinlogging.KotlinLogging


private val logger = KotlinLogging.logger {}

data class PwDataset(
    val id: String? = null,
    val label: String,
    val username: String,
    val password: String
) {
    override fun toString() = "PwDataset(id=$id, label=$label, username=<redacted>, password=<redacted>)"
}

class FlutterAutofillPluginImpl(val context: Context) : MethodCallHandler,
    PluginRegistry.ActivityResultListener, PluginRegistry.NewIntentListener, ActivityAware {

    companion object {
        // some creative way so we have some more or less unique result code? 🤷️
        val REQUEST_CODE_SET_AUTOFILL_SERVICE =
            FlutterAutofillPlugin::class.java.hashCode() and 0xffff

    }

    private val autofillManager by lazy {
        requireNotNull(context.getSystemService(AutofillManager::class.java))
    }
    private val autofillPreferenceStore by lazy { AutofillPreferenceStore.getInstance(context) }
    private var requestSetAutofillServiceResult: Result? = null

    private var activityBinding: ActivityPluginBinding? = null
    private val activity get() = activityBinding?.activity
    private var lastIntent: Intent? = null
        get() = field ?: activity?.intent

    override fun onMethodCall(call: MethodCall, result: Result) {
        logger.debug { "got autofillPreferences: ${autofillPreferenceStore.autofillPreferences}" }
        when (call.method) {
            "hasAutofillServicesSupport" ->
                result.success(true)

            "hasEnabledAutofillServices" ->
                result.success(
                    try {
                        autofillManager.hasEnabledAutofillServices()
                    } catch (e: RuntimeException) {
                        false
                    }
                )

            "disableAutofillServices" -> {
                autofillManager.disableAutofillServices()
                result.success(null)
            }

            "requestSetAutofillService" -> {
                val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                intent.data = Uri.parse("package:com.example.android.autofill.service")
                logger.debug { "enableService(): intent=$intent" }
                requestSetAutofillServiceResult = result
                requireNotNull(activity) { "No Activity available." }
                    .startActivityForResult(
                        intent,
                        REQUEST_CODE_SET_AUTOFILL_SERVICE
                    )
                // result will be delivered in onActivityResult!
            }
            // method available while we are handling an autofill request.
            "getAutofillMetadata" -> {
                val metadata = lastIntent?.getStringExtra(
                    AutofillMetadata.EXTRA_NAME
                )?.let(AutofillMetadata.Companion::fromJsonString)
                logger.debug { "Got metadata: ${hideSensitiveMetadata(metadata)}" }
                result.success(metadata?.toJson())
            }

            "fillRequestedAutomatic" -> {
                val mode = lastIntent?.getStringExtra(
                    "autofill_mode"
                )
                result.success(mode == "/autofill")
            }

            "fillRequestedInteractive" -> {
                val mode = lastIntent?.getStringExtra(
                    "autofill_mode"
                )
                result.success(mode == "/autofill_select")
            }
            // Single dataset - must be sent only in response to an authenticated dataset request ("Use a different entry")
            "resultWithDataset" -> {
                resultWithDataset(call, result)
            }
            // List of datasets - user must then select which one they want to be filled
            // null indicates we are declining to look for any matches
            "resultWithDatasets" -> {
                val sets = call.argument<List<Map<String, String>>>("datasets")
                val list = sets?.map { m ->
                    m["label"]?.let {
                        m["username"]?.let { it1 ->
                            m["password"]?.let { it2 ->
                                PwDataset(
                                    m["id"],
                                    it,
                                    it1,
                                    it2
                                )
                            }
                        }
                    }
                        ?: throw IllegalArgumentException("Invalid dataset object.")
                }
                if (list == null) {
                    resultWithNullDataset(result)
                } else {
                    resultWithDatasets(list, result)
                }
            }

            "getPreferences" -> {
                result.success(
                    autofillPreferenceStore.autofillPreferences.toJsonValue()
                )
            }

            "setPreferences" -> {
                val prefs = call.argument<Map<String, Any>>("preferences")?.let { data ->
                    AutofillPreferences.fromJsonValue(data)
                } ?: throw IllegalArgumentException("Invalid preferences object.")
                autofillPreferenceStore.autofillPreferences = prefs

                // Make sure we have the latest log level configuration
                DynamicLevelLoggingProvider.applyPreference(autofillPreferenceStore.autofillPreferences.enableDebug)
                result.success(true)
            }

            "onSaveComplete" -> {
                // Clearing the lastIntent allows the consumer's code to know if a save request has already been handled.
                lastIntent = null
                activity?.moveTaskToBack(true)
            }

            else -> result.notImplemented()
        }
    }

    private fun hideSensitiveMetadata(metadata: AutofillMetadata?): String {
        if (metadata == null) return ""
        return "appId: ${metadata.appId} (source:${metadata.appIdSource}), domains: ${metadata.webDomains}, compatMode: ${metadata.saveInfo?.isCompatMode}"
    }

    private fun getDrawable(name: String): Int {
        val resources: Resources = context.resources
        return resources.getIdentifier(
            name, "drawable",
            context.packageName
        )
    }

    private fun resultWithDataset(call: MethodCall, result: Result) {
        val id = call.argument<String>("id")
        val label = call.argument<String>("label") ?: "Autofill"
        val username = call.argument<String>("username") ?: ""
        val password = call.argument<String>("password") ?: ""
        if (password.isBlank()) {
            logger.warn { "No known password." }
        }
        resultWithDataset(PwDataset(id, label, username, password), result)
    }

    /**
     * Cancels the authentication and reports `false` if [structure] would fill a different target
     * from the one in the launch intent's metadata, which is what the consumer showed the user and
     * chose the credentials for (see [fillTargetMismatch]). Returns true if the reply was refused.
     */
    private fun declineIfTargetChanged(structure: AssistStructureParser, result: Result): Boolean {
        val shown = lastIntent?.getStringExtra(AutofillMetadata.EXTRA_NAME)?.let {
            runCatching { AutofillMetadata.fromJsonString(it) }.getOrNull()
        }
        val reason = fillTargetMismatch(shown?.fillTarget(), structure.fillTarget()) ?: return false
        logger.warn { "Not filling: $reason since the request was shown to the user." }
        val activity = requireNotNull(this.activity)
        activity.setResult(RESULT_CANCELED)
        activity.finish()
        result.success(false)
        return true
    }

    private fun resultWithNullDataset(result: Result) {
        val activity = requireNotNull(this.activity)
        val replyIntent = Intent()

        // Official docs say we must return null but the API does not allow this. Thus I am
        // guessing that I must omit the entire EXTRA_AUTHENTICATION_RESULT data item
        //.apply {
        //putExtra<Parcelable>(EXTRA_AUTHENTICATION_RESULT, null as Parcelable)
        //}

        activity.setResult(RESULT_OK, replyIntent)
        activity.finish()
        result.success(true)
    }

    private fun resultWithDatasets(pwDatasets: List<PwDataset>, result: Result) {

        val structureParcel: AssistStructure? =
            lastIntent?.extras?.getParcelable(AutofillManager.EXTRA_ASSIST_STRUCTURE)
                ?: activity?.intent?.extras?.getParcelable(
                    AutofillManager.EXTRA_ASSIST_STRUCTURE
                )

        val clientState: Bundle =
            lastIntent?.extras?.getParcelable(AutofillManager.EXTRA_CLIENT_STATE)
                ?: activity?.intent?.extras?.getParcelable(
                    AutofillManager.EXTRA_CLIENT_STATE
                ) ?: Bundle()

        if (structureParcel == null) {
            logger.info { "No structure available. (activity: $activity)" }
            result.success(false)
            return
        }

        val activity = requireNotNull(this.activity)
        if (clientState.getBoolean("isInAppSelection", false)) {
            // The in-app selection flow authenticates a single dataset so the reply must be a
            // single Dataset. Accept the plural call from older consumers by using the first entry.
            logger.warn { "In-app selection flow expects resultWithDataset; got resultWithDatasets with ${pwDatasets.size} entries." }
            if (pwDatasets.isEmpty()) {
                resultWithNullDataset(result)
            } else {
                resultWithDataset(pwDatasets.first(), result)
            }
            return
        }
        val structure = AssistStructureParser(structureParcel, context)
        logger.debug { "DAL: isWebOrigin=${structure.isWebOrigin}, webDomains=${structure.webDomains}" }
        if (declineIfTargetChanged(structure, result)) return
        var totalToReturn = 0
        val isManualRequest = clientState.getBoolean("isManualRequest", false)
        val focusedId = structure.focusedAutofillId

        logger.debug { "isManualRequest: $isManualRequest" }
        logger.debug { "focusedId: $focusedId" }
        logger.debug { "structure: $structure" }
        logger.info { "packageName: ${context.packageName}" }

        var autoFillIdPasswordMatched: AutofillId? = null
        var autoFillIdUsernameMatched: AutofillId? = null
        val (autoFillIdUsernameGuessed, autoFillIdPasswordGuessed) = SaveHelper.guessAutofillIdsForSave(
            structure
        )

        val remoteViews = {
            RemoteViewsHelper.viewsWithNoAuth(
                context.packageName, "Fill Me"
            )
        }

        val metaData = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        ).metaData
        val serviceShortName =
            metaData.getString("com.keevault.flutter_autofill_service.service_short_name")
                ?: "AutoFill"
        val selectAnotherEntryLabel =
            metaData.getString("com.keevault.flutter_autofill_service.select_another_entry")
                ?: "Use a different entry"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val (isFillDialogRequest, inlineRequest: InlineSuggestionsRequest?, maxInlineSuggestionCount) =
                extractClientState(
                    clientState
                )

            // Ignore IMEs that only allow one item because we require at least two
            val respondInline = maxInlineSuggestionCount >= 2

            val fillResponseBuilder = FillResponse.Builder().apply {

                // Even though Android will sometimes fall back to using the non-IME representation,
                // it prevents us from returning more than the maximum number of datasets so we just
                // have to hope that the user doesn't use an IME with a low limit.
                pwDatasets.take(if (respondInline) maxInlineSuggestionCount - 1 else 10).forEachIndexed { i, pw ->
                    val builder = createDatasetBuilder(
                        remoteViews,
                        respondInline,
                        inlineRequest,
                        isFillDialogRequest
                    )
                    builder.apply {
                        setId(pw.id ?: "ds-$i")

                        val filledAutofillIds = mutableSetOf<AutofillId>()
                        if (isManualRequest && focusedId != null) {
                            val focusedType = structure.fieldIds.entries.find { it.value.any { mf -> mf.autofillId == focusedId } }?.key
                            if (focusedType != null) {
                                val valueForFocused = when (focusedType) {
                                    AutofillInputType.Password, AutofillInputType.NewPassword -> if (pw.password.isNotBlank()) pw.password else ""
                                    AutofillInputType.TOTP -> ""
                                    else -> if (pw.username.isNotBlank()) pw.username else ""
                                }
                                if (valueForFocused.isNotBlank()) {
                                    val focusedField = structure.fillableFields().firstOrNull { it.first == focusedType && it.second.autofillId == focusedId }?.second
                                    if (focusedField != null) {
                                        filledAutofillIds.add(focusedId)
                                        configureDataset(
                                            pw.label,
                                            null,
                                            respondInline && i < maxInlineSuggestionCount - 1,
                                            isFillDialogRequest,
                                            valueForFocused,
                                            inlineRequest,
                                            i,
                                            false,
                                            focusedField
                                        )
                                    }
                                }
                            }
                        }
                        structure.fillableFields()
                            .forEach allIds@{ (type, field) ->
                                val isNewAutofillId = filledAutofillIds.add(field.autofillId)
                                logger.debug {
                                    "Adding data set at weight ${field.heuristic.weight} for ${
                                        type.toString().padStart(10)
                                    } for ${field.autofillId} '${field.heuristic.message}' ${"Ignored".takeIf { !isNewAutofillId } ?: ""}"
                                }

                                if (!isNewAutofillId) {
                                    return@allIds
                                }

                                // We may select different autofillIDs for each dataset (although unlikely) but for save purposes,
                                // Android only allows us to select one set of autofillIDs. We pick the first one that contains
                                // any match. Again, there is a small chance this will differ from the set we select when we have
                                // no data available but there aren't likely to be many situations where that actually happens.
                                if (autoFillIdPasswordMatched == null && type == AutofillInputType.Password) {
                                    autoFillIdPasswordMatched = field.autofillId
                                } else if (autoFillIdUsernameMatched == null && (type == AutofillInputType.Email || type == AutofillInputType.UserName)) {
                                    autoFillIdUsernameMatched = field.autofillId
                                }

                                val autoFillValue = when (type) {
                                    AutofillInputType.Password, AutofillInputType.NewPassword -> pw.password
                                    AutofillInputType.TOTP -> ""
                                    else -> pw.username
                                }
                                configureDataset(
                                    pw.label,
                                    null,
                                    respondInline && i < maxInlineSuggestionCount - 1,
                                    isFillDialogRequest,
                                    autoFillValue,
                                    inlineRequest,
                                    i,
                                    false,
                                    field
                                )
                            }
                    }
                    // If we have no fields in the parsed structure then we won't have added them to the builder.
                    // So far we've only seen this happen when the user forces an autofill operation for a field
                    // that would not normally be considered for autofill. More investigation is needed to
                    // discover if there are steps we can take to reject their efforts at an earlier stage
                    // or if we could modify the structure parser to better match with whatever field they
                    // forced upon us. For now, we just make sure we don't crash.
                    val dataset = try { builder.build() } catch (e: IllegalStateException) {
                        null
                    }
                    if (dataset != null) {
                        addDataset(dataset)
                        totalToReturn++
                    }
                }
                val matchHeaderDrawableName =
                    metaData.getString("com.keevault.flutter_autofill_service.match_header_drawable_name")
                val drawableId =
                    if (matchHeaderDrawableName != null) getDrawable(matchHeaderDrawableName) else R.drawable.ic_info_24dp
                val header = RemoteViews(
                    context.packageName,
                    R.layout.multidataset_service_list_item
                ).apply {
                    val countSuffix = if (totalToReturn == 1) "" else "es"
                    setTextViewText(
                        R.id.text,
                        "$totalToReturn $serviceShortName match$countSuffix..."
                    )
                    setImageViewResource(R.id.icon, drawableId)
                }
                setHeader(header)

                if (isFillDialogRequest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setFillDialogTriggerIds(*structure.autoFillIds.toTypedArray())
                    setDialogHeader(header)
                }
            }

            val activityName =
                metaData.getString("com.keevault.flutter_autofill_service.ACTIVITY_NAME")
                    ?: "com.keevault.flutter_autofill_service_example.AutofillActivity"
            logger.debug { "got activity $activityName" }
            val startIntent = getStartIntent(
                activityName,
                structure.appId,
                structure.appIdSource,
                structure.webDomains,
                context,
                "/autofill_select",
                null,
                structure.isWebOrigin,
                structure.appIsBrowser
            )
            val intentSender: IntentSender
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                intentSender = PendingIntent.getActivity(
                    context,
                    IntentHelpers.REQUEST_CODE_SELECT_ANOTHER,
                    startIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
                ).intentSender
            } else {
                @SuppressLint("UnspecifiedImmutableFlag")
                intentSender = PendingIntent.getActivity(
                    context,
                    IntentHelpers.REQUEST_CODE_SELECT_ANOTHER,
                    startIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT
                ).intentSender
            }

            val dataset =
                selectAnotherEntryDataset(
                    structure,
                    metaData,
                    selectAnotherEntryLabel,
                    intentSender,
                    inlineRequest,
                    isFillDialogRequest
                )
            if (dataset != null) {
                fillResponseBuilder.addDataset(dataset)
            }

            if (dataset == null && totalToReturn <= 0) {
                resultWithNullDataset(result)
                return
            }

            val saveInfo = createSaveInfo(
                clientState,
                autoFillIdUsernameGuessed,
                autoFillIdPasswordGuessed,
                autoFillIdUsernameMatched,
                autoFillIdPasswordMatched
            )

            fillResponseBuilder.setClientState(clientState)
            saveInfo?.let { fillResponseBuilder.setSaveInfo(it) }
            val fillResponse = fillResponseBuilder.build()
            val replyIntent = Intent().apply {
                // Send the data back to the service.
                putExtra(EXTRA_AUTHENTICATION_RESULT, fillResponse)
            }

            activity.setResult(RESULT_OK, replyIntent)

        } else {

            val fillResponseBuilder = FillResponse.Builder().apply {

                pwDatasets.take(10).forEachIndexed { i, pw ->
                    val builder = Dataset.Builder(remoteViews())
                    builder.apply {
                        setId(pw.id ?: "ds-$i")

                        val filledAutofillIds = mutableSetOf<AutofillId>()
                        if (isManualRequest && focusedId != null) {
                            val focusedType = structure.fieldIds.entries.find { it.value.any { mf -> mf.autofillId == focusedId } }?.key
                            if (focusedType != null) {
                                val valueForFocused = when (focusedType) {
                                    AutofillInputType.Password, AutofillInputType.NewPassword -> if (pw.password.isNotBlank()) pw.password else ""
                                    AutofillInputType.TOTP -> ""
                                    else -> if (pw.username.isNotBlank()) pw.username else ""
                                }
                                if (valueForFocused.isNotBlank()) {
                                    val focusedField = structure.fillableFields().firstOrNull { it.first == focusedType && it.second.autofillId == focusedId }?.second
                                    if (focusedField != null) {
                                        filledAutofillIds.add(focusedId)
                                        configureDatasetPreS(
                                            pw.label,
                                            null,
                                            valueForFocused,
                                            focusedField
                                        )
                                    }
                                }
                            }
                        }
                        structure.fillableFields()
                            .forEach allIds@{ (type, field) ->
                                val isNewAutofillId = filledAutofillIds.add(field.autofillId)
                                logger.debug {
                                    "Adding data set at weight ${field.heuristic.weight} for ${
                                        type.toString().padStart(10)
                                    } for ${field.autofillId} '${field.heuristic.message}' ${"Ignored".takeIf { !isNewAutofillId } ?: ""}"
                                }

                                if (!isNewAutofillId) {
                                    return@allIds
                                }

                                // We may select different autofillIDs for each dataset (although unlikely) but for save purposes,
                                // Android only allows us to select one set of autofillIDs. We pick the first one that contains
                                // any match. Again, there is a small chance this will differ from the set we select when we have
                                // no data available but there aren't likely to be many situations where that actually happens.
                                if (autoFillIdPasswordMatched == null && type == AutofillInputType.Password) {
                                    autoFillIdPasswordMatched = field.autofillId
                                } else if (autoFillIdUsernameMatched == null && (type == AutofillInputType.Email || type == AutofillInputType.UserName)) {
                                    autoFillIdUsernameMatched = field.autofillId
                                }

                                val autoFillValue = when (type) {
                                    AutofillInputType.Password, AutofillInputType.NewPassword -> pw.password
                                    AutofillInputType.TOTP -> ""
                                    else -> pw.username
                                }
                                configureDatasetPreS(
                                    pw.label,
                                    null,
                                    autoFillValue,
                                    field
                                )
                            }
                        }
                        val dataset = try { builder.build() } catch (e: IllegalStateException) { null }
                        if (dataset != null) {
                            addDataset(dataset)
                            totalToReturn++
                        }
                    }
                val matchHeaderDrawableName =
                    metaData.getString("com.keevault.flutter_autofill_service.match_header_drawable_name")
                val drawableId =
                    if (matchHeaderDrawableName != null) getDrawable(matchHeaderDrawableName) else R.drawable.ic_info_24dp
                val header = RemoteViews(
                    context.packageName,
                    R.layout.multidataset_service_list_item
                ).apply {
                    val countSuffix = if (totalToReturn == 1) "" else "es"
                    setTextViewText(
                        R.id.text,
                        "$totalToReturn $serviceShortName match$countSuffix..."
                    )
                    setImageViewResource(R.id.icon, drawableId)
                }
                setHeader(header)
            }

            val activityName =
                metaData.getString("com.keevault.flutter_autofill_service.ACTIVITY_NAME")
                    ?: "com.keevault.flutter_autofill_service_example.AutofillActivity"
            logger.debug { "got activity $activityName" }
            val startIntent = getStartIntent(
                activityName,
                structure.appId,
                structure.appIdSource,
                structure.webDomains,
                context,
                "/autofill_select",
                null,
                structure.isWebOrigin,
                structure.appIsBrowser
            )
            val intentSender: IntentSender
            @SuppressLint("UnspecifiedImmutableFlag")
            intentSender = PendingIntent.getActivity(
                context,
                IntentHelpers.REQUEST_CODE_SELECT_ANOTHER,
                startIntent,
                PendingIntent.FLAG_CANCEL_CURRENT
            ).intentSender

            val dataset = selectAnotherEntryDatasetPreS(
                structure,
                metaData,
                selectAnotherEntryLabel,
                intentSender,
            )

            if (dataset != null) {
                fillResponseBuilder.addDataset(dataset)
            }

            if (dataset == null && totalToReturn <= 0) {
            resultWithNullDataset(result)
            return
            }
            val saveInfo = createSaveInfo(
                clientState,
                autoFillIdUsernameGuessed,
                autoFillIdPasswordGuessed,
                autoFillIdUsernameMatched,
                autoFillIdPasswordMatched
            )

            fillResponseBuilder.setClientState(clientState)
            saveInfo?.let { fillResponseBuilder.setSaveInfo(it) }
            val fillResponse = fillResponseBuilder.build()
            val replyIntent = Intent().apply {
                // Send the data back to the service.
                putExtra(EXTRA_AUTHENTICATION_RESULT, fillResponse)
            }

            activity.setResult(RESULT_OK, replyIntent)
        }
        if (totalToReturn == 0) {
            resultWithNullDataset(result)
            return
        }
        activity.finish()
        result.success(true)
    }

    private fun resultWithDataset(pwDataset: PwDataset, result: Result) {
        val structureParcel: AssistStructure? =
            lastIntent?.extras?.getParcelable(AutofillManager.EXTRA_ASSIST_STRUCTURE)
                ?: activity?.intent?.extras?.getParcelable(
                    AutofillManager.EXTRA_ASSIST_STRUCTURE
                )

        val clientState: Bundle =
            lastIntent?.extras?.getParcelable(AutofillManager.EXTRA_CLIENT_STATE)
                ?: activity?.intent?.extras?.getParcelable(
                    AutofillManager.EXTRA_CLIENT_STATE
                ) ?: Bundle()

        if (structureParcel == null) {
            logger.info { "No structure available. (activity: $activity)" }
            result.success(false)
            return
        }

        val activity = requireNotNull(this.activity)
        val inAppSelection = clientState.getBoolean("isInAppSelection", false)
        if (!inAppSelection && lastIntent?.getStringExtra("autofill_mode") != "/autofill_select") {
            logger.warn { "resultWithDataset called outside of the in-app selection flow and outside of a \"Use a different entry\" launch." }
        }
        val structure = AssistStructureParser(structureParcel, context)
        logger.debug { "DAL: isWebOrigin=${structure.isWebOrigin}, webDomains=${structure.webDomains}" }
        logger.debug { "structure: $structure" }
        logger.info { "packageName: ${context.packageName}" }
        if (declineIfTargetChanged(structure, result)) return

        val remoteViews = {
            RemoteViewsHelper.viewsWithNoAuth(
                context.packageName, "Fill Me"
            )
        }

        var autoFillIdPasswordMatched: AutofillId? = null
        var autoFillIdUsernameMatched: AutofillId? = null
        val (autoFillIdUsernameGuessed, autoFillIdPasswordGuessed) = SaveHelper.guessAutofillIdsForSave(
            structure
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val (isFillDialogRequest, inlineRequest: InlineSuggestionsRequest?, maxInlineSuggestionCount) = extractClientState(
                clientState
            )

            // The legacy reply shares the list with a "Use a different entry" item so needs two
            // slots. The in-app selection reply is a single dataset so needs only one.
            val respondInline = maxInlineSuggestionCount >= (if (inAppSelection) 1 else 2)

            val builder =
                createDatasetBuilder(remoteViews, respondInline, inlineRequest, isFillDialogRequest)
            builder.apply {
                setId(pwDataset.id ?: "ds-0")
                val filledAutofillIds = mutableSetOf<AutofillId>()
                structure.fillableFields().forEach allIds@{ (type, field) ->
                    val isNewAutofillId = filledAutofillIds.add(field.autofillId)
                    logger.debug {
                        "Adding data set at weight ${field.heuristic.weight} for ${
                            type.toString().padStart(10)
                        } for ${field.autofillId} ${field.heuristic.message} ${"Ignored".takeIf { !isNewAutofillId } ?: ""}"
                    }

                    if (!isNewAutofillId) {
                        return@allIds
                    }

                    if (autoFillIdPasswordMatched == null && type == AutofillInputType.Password) {
                        autoFillIdPasswordMatched = field.autofillId
                    } else if (autoFillIdUsernameMatched == null && (type == AutofillInputType.Email || type == AutofillInputType.UserName)) {
                        autoFillIdUsernameMatched = field.autofillId
                    }

                    val autoFillValue = when (type) {
                        AutofillInputType.Password, AutofillInputType.NewPassword -> pwDataset.password
                        AutofillInputType.TOTP -> ""
                        else -> pwDataset.username
                    }
                    configureDataset(
                        pwDataset.label,
                        null,
                        respondInline,
                        isFillDialogRequest,
                        autoFillValue,
                        inlineRequest,
                        -1,
                        false,
                        field
                    )

                }
            }
            val dataset = builder.build()

            if (inAppSelection) {
                // The service authenticated a single dataset so the reply is a Dataset, not a
                // FillResponse. SaveInfo already travelled in the initial response.
                val ephemeral = clientState.getBoolean("isEphemeralDataset", true)
                val replyIntent = Intent().apply {
                    putExtra(EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET, ephemeral)
                    putExtra(EXTRA_AUTHENTICATION_RESULT, dataset)
                }
                activity.setResult(RESULT_OK, replyIntent)
                activity.finish()
                result.success(true)
                return
            }

            val fillResponseBuilder = FillResponse.Builder().apply {
                addDataset(dataset)
                setClientState(clientState)
            }

            val saveInfo = createSaveInfo(
                clientState,
                autoFillIdUsernameGuessed,
                autoFillIdPasswordGuessed,
                autoFillIdUsernameMatched,
                autoFillIdPasswordMatched
            )
            saveInfo?.let { fillResponseBuilder.setSaveInfo(it) }

            val fillResponse = fillResponseBuilder.build()

            val replyIntent = Intent().apply {
                putExtra(EXTRA_AUTHENTICATION_RESULT, fillResponse)
            }

            activity.setResult(RESULT_OK, replyIntent)
        } else {
            val builder = Dataset.Builder(remoteViews())
            builder.apply {
                setId(pwDataset.id ?: "ds-0")
                val filledAutofillIds = mutableSetOf<AutofillId>()
                structure.fillableFields()
                    .forEach allIds@{ (type, field) ->
                        val isNewAutofillId = filledAutofillIds.add(field.autofillId)
                        logger.debug {
                            "Adding data set at weight ${field.heuristic.weight} for ${
                                type.toString().padStart(10)
                            } for ${field.autofillId} ${field.heuristic.message} ${"Ignored".takeIf { !isNewAutofillId } ?: ""}"
                        }

                        if (!isNewAutofillId) {
                            return@allIds
                        }

                        if (autoFillIdPasswordMatched == null && type == AutofillInputType.Password) {
                            autoFillIdPasswordMatched = field.autofillId
                        } else if (autoFillIdUsernameMatched == null && (type == AutofillInputType.Email || type == AutofillInputType.UserName)) {
                            autoFillIdUsernameMatched = field.autofillId
                        }

                        val autoFillValue = when (type) {
                            AutofillInputType.Password, AutofillInputType.NewPassword -> pwDataset.password
                            AutofillInputType.TOTP -> ""
                            else -> pwDataset.username
                        }
                        configureDatasetPreS(
                            pwDataset.label,
                            null,
                            autoFillValue,
                            field
                        )

                    }
            }
            val dataset = builder.build()

            if (inAppSelection) {
                // Dataset authentication (see above). The ephemeral extra needs API 31+ so the
                // framework's default "replace the locked dataset" behaviour applies here.
                val replyIntent = Intent().apply {
                    putExtra(EXTRA_AUTHENTICATION_RESULT, dataset)
                }
                activity.setResult(RESULT_OK, replyIntent)
                activity.finish()
                result.success(true)
                return
            }

            val fillResponseBuilder = FillResponse.Builder().apply {
                addDataset(dataset)
                setClientState(clientState)
            }

            val saveInfo = createSaveInfo(
                clientState,
                autoFillIdUsernameGuessed,
                autoFillIdPasswordGuessed,
                autoFillIdUsernameMatched,
                autoFillIdPasswordMatched
            )
            saveInfo?.let { fillResponseBuilder.setSaveInfo(it) }

            val fillResponse = fillResponseBuilder.build()

            val replyIntent = Intent().apply {
                putExtra(EXTRA_AUTHENTICATION_RESULT, fillResponse)
            }

            activity.setResult(RESULT_OK, replyIntent)
        }
        activity.finish()
        result.success(true)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createDatasetBuilder(
        remoteViews: () -> RemoteViews,
        respondInline: Boolean,
        inlineRequest: InlineSuggestionsRequest?,
        isFillDialogRequest: Boolean
    ): Dataset.Builder {
        val presentations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val presentationBuilder = Presentations.Builder().setMenuPresentation(remoteViews())
            if (respondInline) presentationBuilder.setInlinePresentation(
                viewsWithNoAuth(
                    "Fill me",
                    inlineRequest!!.inlinePresentationSpecs.first(), null, context, false,
                )!!
            )
            if (isFillDialogRequest) presentationBuilder.setDialogPresentation(remoteViews())
            presentationBuilder.build()
        } else {
            null
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Dataset.Builder(presentations!!)
        } else Dataset.Builder(remoteViews())
        return builder
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun extractClientState(clientState: Bundle): Triple<Boolean, InlineSuggestionsRequest?, Int> {
        val isFillDialogRequest =
            if (clientState.containsKey("isFillDialogRequest")) clientState.getBoolean("isFillDialogRequest") else false

        if (!autofillPreferenceStore.autofillPreferences.enableIMERequests) {
            return Triple(isFillDialogRequest, null, 0)
        }

        val inlineRequest: InlineSuggestionsRequest? =
            // We need authentication to work(!) so inline suggestions are only possible on S+
            lastIntent?.extras?.getParcelable(AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST)
                ?: activity?.intent?.extras?.getParcelable(
                    AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST
                )

        val maxInlineSuggestionCount = inlineRequest?.maxSuggestionCount ?: 0

        return Triple(isFillDialogRequest, inlineRequest, maxInlineSuggestionCount)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun Dataset.Builder.configureDataset(
        label: String,
        drawableId: Int?,
        respondInline: Boolean,
        dialogFillEnabled: Boolean,
        autoFillValue: String?,
        inlineRequest: InlineSuggestionsRequest?,
        datasetIndex: Int,
        isPinned: Boolean,
        field: MatchedField
    ) {
        val menuPresentation = RemoteViewsHelper.viewsWithNoAuthOptionalIcon(
            context.packageName, label, drawableId
        )
        val inlinePresentation =
            if (respondInline) {
                viewsWithNoAuth(
                    label,
                    inlineRequest!!.inlinePresentationSpecs.elementAtOrElse(
                        datasetIndex
                    ) { inlineRequest.inlinePresentationSpecs.last() },
                    null,
                    context,
                    isPinned,
                    if (isPinned) pinnedIcon() else R.drawable.ic_person_24dp,
                    !isPinned,
                )
            } else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val fieldBuilder = Field.Builder()
                .setValue(AutofillValue.forText(autoFillValue))
            val presentationsBuilder = Presentations.Builder()
            presentationsBuilder.setMenuPresentation(menuPresentation)
            if (inlinePresentation != null) {
                presentationsBuilder.setInlinePresentation(
                    inlinePresentation
                )
            }
            if (dialogFillEnabled) {
                presentationsBuilder.setDialogPresentation(
                    menuPresentation
                )
            }
            fieldBuilder.setPresentations(presentationsBuilder.build())
            setField(field.autofillId, fieldBuilder.build())
        } else {
            if (inlinePresentation != null) {
                setValue(
                    field.autofillId,
                    AutofillValue.forText(autoFillValue),
                    menuPresentation,
                    inlinePresentation
                )
            } else {
                setValue(
                    field.autofillId,
                    AutofillValue.forText(autoFillValue),
                    menuPresentation
                )
            }
        }
    }

    private fun pinnedIcon(): Int {
        val metaData = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        ).metaData
        val pinnedDrawableName =
            metaData.getString("com.keevault.flutter_autofill_service.pinned_drawable_name")
        return if (pinnedDrawableName != null) getDrawable(pinnedDrawableName) else R.drawable.ic_lock_24dp
    }

    private fun Dataset.Builder.configureDatasetPreS(
        label: String,
        drawableId: Int?,
        autoFillValue: String?,
        field: MatchedField
    ) {
        val menuPresentation = RemoteViewsHelper.viewsWithNoAuthOptionalIcon(
            context.packageName, label, drawableId
        )
        setValue(
            field.autofillId,
            AutofillValue.forText(autoFillValue),
            menuPresentation
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun selectAnotherEntryDataset(
        structure: AssistStructureParser,
        metaData: Bundle,
        selectAnotherEntryLabel: String,
        intentSender: IntentSender,
        inlineRequest: InlineSuggestionsRequest?,
        isFillDialogRequest: Boolean,
    ) = try { Dataset.Builder().apply {

        structure.fieldIds.flatMap { entry ->
            entry.value.map { entry.key to it }
        }.sortedByDescending { it.second.heuristic.weight }.forEach allIds@{ (type, field) ->
            val selectAnotherEntryDrawableName =
                metaData.getString("com.keevault.flutter_autofill_service.select_another_entry_drawable_name")
            val drawableId = if (selectAnotherEntryDrawableName != null) getDrawable(
                selectAnotherEntryDrawableName
            ) else R.drawable.ic_baseline_playlist_add_24
            // On Android <13, this gets replaced when user interacts with it so we can't
            // offer this more than once - user will have to refresh the
            // web page or restart the app if they make a mistake.

            configureDataset(
                selectAnotherEntryLabel,
                drawableId,
                inlineRequest != null,
                isFillDialogRequest,
                null,
                inlineRequest,
                -1,
                true,
                field
            )
        }
        setId("select-another")
        setAuthentication(intentSender)
    }.build() } catch (e: IllegalStateException) {
        logger.warn {
                            "Failed to add pick another item dataset: $e"
                        }
        null
    }

    private fun selectAnotherEntryDatasetPreS(
        structure: AssistStructureParser,
        metaData: Bundle,
        selectAnotherEntryLabel: String,
        intentSender: IntentSender,
    ) = try { Dataset.Builder().apply {

        structure.fieldIds.flatMap { entry ->
            entry.value.map { entry.key to it }
        }.sortedByDescending { it.second.heuristic.weight }.forEach allIds@{ (type, field) ->
            val selectAnotherEntryDrawableName =
                metaData.getString("com.keevault.flutter_autofill_service.select_another_entry_drawable_name")
            val drawableId = if (selectAnotherEntryDrawableName != null) getDrawable(
                selectAnotherEntryDrawableName
            ) else R.drawable.ic_baseline_playlist_add_24
            // On Android <13, this gets replaced when user interacts with it so we can't
            // offer this more than once - user will have to refresh the
            // web page or restart the app if they make a mistake.

            configureDatasetPreS(
                selectAnotherEntryLabel,
                drawableId,
                null,
                field
            )
        }
        setId("select-another")
        setAuthentication(intentSender)
    }.build() } catch (e: IllegalStateException) {
        logger.warn {
                            "Failed to add pick another item dataset: $e"
                        }
        null
    }

    override fun onNewIntent(intent: Intent): Boolean {
        lastIntent = intent
        logger.info { "We got a new intent. ${IntentHelpers.describeForLog(intent)}" }
        return false
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        logger.debug {
            "got activity result for $requestCode" +
                    " (our: $REQUEST_CODE_SET_AUTOFILL_SERVICE) result: $resultCode"
        }
        if (requestCode == REQUEST_CODE_SET_AUTOFILL_SERVICE) {
            requestSetAutofillServiceResult?.let { result ->
                requestSetAutofillServiceResult = null
                result.success(resultCode == RESULT_OK)
            } ?: logger.warn { "Got activity result, but did not have a requestResult set." }
            return true
        }
        return false
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addActivityResultListener(this)
        binding.addOnNewIntentListener(this)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding?.removeOnNewIntentListener(this)
        activityBinding = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }
}

class FlutterAutofillPlugin : FlutterPlugin, ActivityAware {

    private var impl: FlutterAutofillPluginImpl? = null
    private var channel: MethodChannel? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val channel =
            MethodChannel(binding.binaryMessenger, "com.keevault/flutter_autofill_service")
        // This process may never have created the autofill service (normal app launch, or an
        // autofill activity restarted after process death), so apply the stored preference now
        // rather than waiting for Dart to call setPreferences.
        DynamicLevelLoggingProvider.applyPreference(
            AutofillPreferenceStore.getInstance(binding.applicationContext).autofillPreferences.enableDebug
        )
        impl = FlutterAutofillPluginImpl(binding.applicationContext)
        channel.setMethodCallHandler(impl)
        this.channel = channel
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    @SuppressLint("NewApi")
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        impl?.onAttachedToActivity(binding)
    }

    @SuppressLint("NewApi")
    override fun onDetachedFromActivityForConfigChanges() {
        impl?.onDetachedFromActivityForConfigChanges()
    }

    @SuppressLint("NewApi")
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        impl?.onReattachedToActivityForConfigChanges(binding)
    }

    @SuppressLint("NewApi")
    override fun onDetachedFromActivity() {
        impl?.onDetachedFromActivity()
    }
}
