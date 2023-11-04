package com.keevault.flutter_autofill_service

import android.R.attr.value
import android.annotation.SuppressLint
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
import org.tinylog.Level


private val logger = KotlinLogging.logger {}

data class PwDataset(
        val label: String,
        val username: String,
        val password: String
)

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
                result.success(try { autofillManager.hasEnabledAutofillServices() } catch (e: RuntimeException) { false })
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
                        .startActivityForResult(intent,
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
                    m["label"]?.let { m["username"]?.let { it1 -> m["password"]?.let { it2 -> PwDataset(it, it1, it2) } } }
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
                val provider = org.tinylog.provider.ProviderRegistry.getLoggingProvider() as DynamicLevelLoggingProvider;
                provider.activeLevel = if (autofillPreferenceStore.autofillPreferences.enableDebug) Level.TRACE else Level.OFF;
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
        if (metadata == null) return "";
        return "names: ${metadata.packageNames}, domains: ${metadata.webDomains}, compatMode: ${metadata.saveInfo?.isCompatMode}"
    }

    private fun getDrawable(name: String): Int {
        val resources: Resources = context.resources
        return resources.getIdentifier(name, "drawable",
                context.packageName)
    }

    private fun resultWithDataset(call: MethodCall, result: Result) {
        val label = call.argument<String>("label") ?: "Autofill"
        val username = call.argument<String>("username") ?: ""
        val password = call.argument<String>("password") ?: ""
        if (password.isBlank()) {
            logger.warn { "No known password." }
        }
        resultWithDataset(PwDataset(label, username, password), result)
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

        val (isFillDialogRequest, inlineRequest: InlineSuggestionsRequest?, maxInlineSuggestionCount) = extractClientState(
            clientState
        )
//        //TODO: Stop hardcoding this if we find a way and desire to support Android 11+ IME autofill
//        val respondInline = false

        // Ignore IMEs that only allow one item because we require at least two
        val respondInline =  maxInlineSuggestionCount >= 2

        if (structureParcel == null) {
            logger.info { "No structure available. (activity: $activity)" }
            result.success(false)
            return
        }

        val activity = requireNotNull(this.activity)
        val structure = AssistStructureParser(structureParcel)
        var totalToReturn = 0

        logger.debug { "structure: $structure" }
        logger.info { "packageName: ${context.packageName}" }

        var autoFillIdPasswordMatched: AutofillId? = null
        var autoFillIdUsernameMatched: AutofillId? = null
        val (autoFillIdPasswordGuessed, autoFillIdUsernameGuessed) = SaveHelper.guessAutofillIdsForSave(structure)

        val remoteViews = {
            RemoteViewsHelper.viewsWithNoAuth(
                    context.packageName, "Fill Me"
            )
        }
//        structure.fieldIds.values.forEach { it.sortByDescending { it.heuristic.weight } }

        val metaData = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        val serviceShortName = metaData.getString("com.keevault.flutter_autofill_service.service_short_name") ?: "AutoFill"
        val selectAnotherEntryLabel = metaData.getString("com.keevault.flutter_autofill_service.select_another_entry") ?: "Use a different entry"

        val fillResponseBuilder = FillResponse.Builder().apply {

            //TODO: Am assuming we can continue to return more than the demanded limit of the IME as long
            // as we don't include more than that number of inlinepresentations but if crashes or problems
            // happen we need to try restricting the total number of results instead, ensuring an extra
            // space at the end for the "pick something else" button
            //pwDatasets.take(if (respondInline) maxInlineSuggestionCount - 1 else 10).forEachIndexed { i, pw ->
            pwDatasets.take(10).forEachIndexed { i, pw ->
                val builder =
                    createDatasetBuilder(remoteViews, respondInline, inlineRequest, isFillDialogRequest)
                builder.apply {
                    setId("test ${pw.username} ${pw.password} ${pw.label}")

                    // No idea what the focussed-specific behaviour achieves and have had no feedback about
                    // it for a couple of years so am deprecating it, but can quickly reinstate if we
                    // ever find out a use for it.
//                            structure.allNodes.forEach { node ->
//                                if (node.isFocused && node.autofillId != null) {
//                                    logger.debug("Setting focus node. ${node.autofillId}")
//                                    val nonInlineResponse = RemoteViews(
//                                            context.packageName,
//                                            android.R.layout.simple_list_item_1
//                                    ).apply {
//                                        setTextViewText(android.R.id.text1, pw.label + " (focussed)")
//                                    }
//                                    val autoFillValue = AutofillValue.forText(pw.username)
////                                    var wasSetInline = false
////                                    if (respondInline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
////                                        val inlineResponse = InlinePresentationHelper.viewsWithNoAuth(pw.username,
////                                                inlineRequest!!.inlinePresentationSpecs.elementAtOrElse(
////                                                        i) { inlineRequest.inlinePresentationSpecs.last() },
////                                                null, context)
////                                        if (inlineResponse != null) {
////                                            setValue(
////                                                    node.autofillId!!,
////                                                    autoFillValue,
////                                                    nonInlineResponse,
////                                                    inlineResponse
////                                            )
////                                            wasSetInline = true
////                                        }
////                                    }
////                                    if (!wasSetInline) {
//                                        setValue(
//                                                node.autofillId!!,
//                                                autoFillValue,
//                                                nonInlineResponse)
////                                    }
//                                }
//                            }
                    val filledAutofillIds = mutableSetOf<AutofillId>()
                    structure.fieldIds.flatMap { mapEntry ->
                        mapEntry.value.map { mapEntry.key to it }
                    }.sortedByDescending { it.second.heuristic.weight }.forEach allIds@{ (type, field) ->
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
                        if (autoFillIdPasswordMatched != null && type == AutofillInputType.Password) {
                            autoFillIdPasswordMatched = field.autofillId
                        } else if (autoFillIdUsernameMatched != null && (type == AutofillInputType.Email || type == AutofillInputType.UserName)) {
                            autoFillIdUsernameMatched = field.autofillId
                        }

                        val autoFillValue = when (type) {
                            AutofillInputType.Password, AutofillInputType.NewPassword -> pw.password
                            AutofillInputType.TOTP -> ""
                            else -> pw.username
                        }
                        configureDataset(pw.label, null, respondInline && i < maxInlineSuggestionCount - 1, isFillDialogRequest, autoFillValue, inlineRequest, i, false, field)
                    }
                }
                addDataset(builder.build())
                totalToReturn++
            }
            val matchHeaderDrawableName = metaData.getString("com.keevault.flutter_autofill_service.match_header_drawable_name")
            val drawableId = if (matchHeaderDrawableName != null) getDrawable(matchHeaderDrawableName) else R.drawable.ic_info_24dp
            val header = RemoteViews(
                    context.packageName,
                    R.layout.multidataset_service_list_item
            ).apply {
                val countSuffix = if (totalToReturn == 1) "" else "es"
                setTextViewText(R.id.text, "$totalToReturn $serviceShortName match$countSuffix...")
                setImageViewResource(R.id.icon, drawableId)
            }
            setHeader(header)

            if (isFillDialogRequest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setFillDialogTriggerIds(*structure.autoFillIds.toTypedArray())
                setDialogHeader(header)
            }
        }

        val activityName = metaData.getString("com.keevault.flutter_autofill_service.ACTIVITY_NAME") ?: "com.keevault.flutter_autofill_service_example.AutofillActivity"
        logger.debug { "got activity $activityName" }
        val startIntent = getStartIntent(activityName, structure.packageNames, structure.webDomains, context, "/autofill_select", null)
        val intentSender: IntentSender
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            intentSender = PendingIntent.getActivity(
                    context,
                    1230,
                    startIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            ).intentSender
        } else {
            @SuppressLint("UnspecifiedImmutableFlag")
            intentSender = PendingIntent.getActivity(
                    context,
                    1230,
                    startIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT
            ).intentSender
        }

        fillResponseBuilder.addDataset(
            selectAnotherEntryDataset(structure, metaData, selectAnotherEntryLabel, intentSender, inlineRequest, isFillDialogRequest)
        )
        val saveInfo = createSaveInfo(clientState, autoFillIdUsernameGuessed, autoFillIdPasswordGuessed, autoFillIdUsernameMatched, autoFillIdPasswordMatched)

        fillResponseBuilder.setClientState(clientState)
        saveInfo?.let {fillResponseBuilder.setSaveInfo(it)}
        val fillResponse = fillResponseBuilder.build()
        val replyIntent = Intent().apply {
            // Send the data back to the service.
            putExtra(EXTRA_AUTHENTICATION_RESULT, fillResponse)
        }

        activity.setResult(RESULT_OK, replyIntent)
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
        val (isFillDialogRequest, inlineRequest: InlineSuggestionsRequest?, maxInlineSuggestionCount) = extractClientState(
            clientState
        )

        // Ignore IMEs that only allow one item because we require at least two.
        // We are returning a single dataset so maybe this restriction is not
        // needed? but then again maybe it would be confusing for user to see their
        // selected item come up in the keyboard only after picking a different
        // entry so we use this as a proxy for when we need to disable IME integration
        val respondInline =  maxInlineSuggestionCount >= 2

        if (structureParcel == null) {
            logger.info { "No structure available. (activity: $activity)" }
            result.success(false)
            return
        }

        val activity = requireNotNull(this.activity)
        val structure = AssistStructureParser(structureParcel)
        logger.debug { "structure: $structure" }
        logger.info { "packageName: ${context.packageName}" }

        val remoteViews = {
            RemoteViewsHelper.viewsWithNoAuth(
                    context.packageName, "Fill Me"
            )
        }
        val builder =
            createDatasetBuilder(remoteViews, respondInline, inlineRequest, isFillDialogRequest)
        builder.apply {
            setId("test ${pwDataset.username} ${pwDataset.password} ${pwDataset.label}")
            val filledAutofillIds = mutableSetOf<AutofillId>()
            structure.fieldIds.flatMap { entry ->
                entry.value.map { entry.key to it }
            }.sortedByDescending { it.second.heuristic.weight }.forEach allIds@{ (type, field) ->
                val isNewAutofillId = filledAutofillIds.add(field.autofillId)
                logger.debug {
                    "Adding data set at weight ${field.heuristic.weight} for ${
                        type.toString().padStart(10)
                    } for ${field.autofillId} ${field.heuristic.message} ${"Ignored".takeIf { !isNewAutofillId } ?: ""}"
                }

                if (!isNewAutofillId) {
                    return@allIds
                }

                val autoFillValue = when (type) {
                    AutofillInputType.Password, AutofillInputType.NewPassword -> pwDataset.password
                    AutofillInputType.TOTP -> ""
                    else -> pwDataset.username
                }
                configureDataset(pwDataset.label, null, respondInline, isFillDialogRequest, autoFillValue, inlineRequest, -1, false, field)

            }
        }
        val datasetResponse = builder.build()

        val replyIntent = Intent().apply {
            // Send the data back to the service.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putExtra(EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET, datasetResponse)
            } else {
                putExtra(EXTRA_AUTHENTICATION_RESULT, datasetResponse)
            }
        }

        activity.setResult(RESULT_OK, replyIntent)
        activity.finish()
        result.success(true)
    }

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
                    inlineRequest!!.inlinePresentationSpecs.first(), null, context,false
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

    private fun extractClientState(clientState: Bundle): Triple<Boolean, InlineSuggestionsRequest?, Int> {
        val isFillDialogRequest =
            if (clientState.containsKey("isFillDialogRequest")) clientState.getBoolean("isFillDialogRequest") else false

        if (!autofillPreferenceStore.autofillPreferences.enableIMERequests) {
            return Triple(isFillDialogRequest, null, 0)
        }

        val inlineRequest: InlineSuggestionsRequest? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // We need authentication to work(!) so inline suggestions are only possible on S+
                lastIntent?.extras?.getParcelable(AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST)
                    ?: activity?.intent?.extras?.getParcelable(
                        AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST
                    )
            } else {
                null
            }

        val maxInlineSuggestionCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            inlineRequest?.maxSuggestionCount ?: 0
        } else 0
        return Triple(isFillDialogRequest, inlineRequest, maxInlineSuggestionCount)
    }

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
            if (respondInline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                viewsWithNoAuth(
                    label, //TODO: changed from autofillvalue - probably was an old bug but check.
                    inlineRequest!!.inlinePresentationSpecs.elementAtOrElse(
                        datasetIndex
                    ) { inlineRequest.inlinePresentationSpecs.last() },
                    null, context, isPinned,
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
            if (inlinePresentation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    private fun selectAnotherEntryDataset(
        structure: AssistStructureParser,
        metaData: Bundle,
        selectAnotherEntryLabel: String,
        intentSender: IntentSender,
        inlineRequest: InlineSuggestionsRequest?,
        isFillDialogRequest: Boolean,
    ) = Dataset.Builder().apply {

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

            configureDataset(selectAnotherEntryLabel, drawableId, inlineRequest != null, isFillDialogRequest, null, inlineRequest, -1, true, field)
        }
        setId("test pick another item")
        setAuthentication(intentSender)
    }.build()

    override fun onNewIntent(intent: Intent): Boolean {
        lastIntent = intent
        logger.info {
            "We got a new intent. $intent (extras: ${
                intent.extras?.keySet()?.map {
                    it to intent.extras?.get(
                            it
                    )
                }
            })"
        }
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
        val channel = MethodChannel(binding.binaryMessenger, "com.keevault/flutter_autofill_service")
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
