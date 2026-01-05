package com.keevault.flutter_autofill_service

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.tinylog.Level

private val logger = KotlinLogging.logger {}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class FlutterCredentialProviderService : CredentialProviderService() {

    private lateinit var autofillPreferenceStore: AutofillPreferenceStore

    override fun onCreate() {
        super.onCreate()
        autofillPreferenceStore = AutofillPreferenceStore.getInstance(applicationContext)
        System.setProperty("logs.folder", filesDir.absolutePath + "/logs");
        val provider = org.tinylog.provider.ProviderRegistry.getLoggingProvider() as DynamicLevelLoggingProvider;
        provider.activeLevel = if (autofillPreferenceStore.autofillPreferences.enableDebug) Level.TRACE else Level.OFF;
        logger.debug { "Credential Provider service was created. debug: ${autofillPreferenceStore.autofillPreferences.enableDebug}" }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        val response: BeginCreateCredentialResponse? = processCreateCredentialRequest(request)
        if (response != null) {
            callback.onResult(response)
        } else {
            callback.onError(CreateCredentialUnknownException())
        }
    }

    fun processCreateCredentialRequest(request: BeginCreateCredentialRequest): BeginCreateCredentialResponse? {
        when (request) {
            is BeginCreatePublicKeyCredentialRequest -> {
                // Don't support passkeys - return null to indicate no support
                logger.info { "Passkey creation requested but not supported by this provider" }
                return null
            }
            is BeginCreatePasswordCredentialRequest -> {
                // Handle password save requests
                logger.info { "Password credential creation requested" }
                return handleCreatePasswordQuery(request)
            }
        }
        // Request not supported
        return null
    }

    private fun handleCreatePasskeyQuery(
        request: BeginCreatePublicKeyCredentialRequest
    ): BeginCreateCredentialResponse {

        logger.info { "handleCreatePasskeyQuery" }

        val createEntries: MutableList<CreateEntry> = mutableListOf()
        createEntries.add( CreateEntry(
            PERSONAL_ACCOUNT_ID,
            createNewPendingIntent(PERSONAL_ACCOUNT_ID, CREATE_PASSKEY_INTENT)
        ))


        logger.trace { "Trying to fetch package info." }
        val activityName =
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).run {
                metaData.getString("com.keevault.flutter_autofill_service.CREATE_PASSKEY_ACTIVITY_NAME")
                    ?: metaData.getString("com.keevault.flutter_autofill_service.ACTIVITY_NAME")
            } ?: "com.keevault.flutter_autofill_service_example.AutofillActivity"
        logger.debug { "got activity $activityName" }

        createEntries.add( CreateEntry(
            "main",
            createNewPendingIntent("main_id", activityName)
        ))

        return BeginCreateCredentialResponse(createEntries)
    }

    private fun handleCreatePasswordQuery(request: BeginCreatePasswordCredentialRequest): BeginCreateCredentialResponse {
        logger.info { "Creating password credential entry for save request" }
        
        val context = applicationContext
        
        // Get the activity name from metadata, with fallback
        val activityName = try {
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).run {
                metaData.getString("com.keevault.flutter_autofill_service.CREATE_PASSWORD_ACTIVITY_NAME")
                    ?: metaData.getString("com.keevault.flutter_autofill_service.ACTIVITY_NAME")
            } ?: "com.keevault.flutter_autofill_service_example.AutofillActivity"
        } catch (e: Exception) {
            logger.error(e) { "Error getting activity name from metadata" }
            "com.keevault.flutter_autofill_service_example.AutofillActivity"
        }
        
        logger.debug { "Using activity: $activityName for password save" }

        val startIntent = Intent()
        startIntent.setClassName(context, activityName)
        startIntent.putExtra("autofill_mode", "/credential_manager_create_password")
        //TODO: support multiple profiles
        //startIntent.putExtra(EXTRA_KEY_ACCOUNT_ID, accountId)
        startIntent.setPackage(context.packageName)
        startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            CREATE_PASSWORD_REQUEST_CODE,
            startIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Get the app label for display
        val displayName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: Exception) {
            context.getString(android.R.string.unknownName)
        }
        
        // Create an entry that will be shown to the user
        val createEntry = CreateEntry.Builder(
            accountName = displayName,
            pendingIntent = pendingIntent
        )
            .setDescription("Save password to $displayName")
            .build()

        return BeginCreateCredentialResponse(listOf(createEntry))
    }

    private fun createNewPendingIntent(accountId: String, action: String): PendingIntent {
        val intent = Intent(action).setPackage(packageName)

        //TODO: The corresponding Activity should be set up to surface any required Biometric prompt, confirmation or selection required.

        //TODO: I think we need just one request code cos we have just one entry (main) but I have
        // no idea if multiple apps using this library on the same device will work with this hardcoded.
        // Your PendingIntent must be constructed with a unique request code so that each entry can have its own corresponding PendingIntent.
        val entryRequestCode = 7363
        return PendingIntent.getActivity(
            applicationContext, entryRequestCode,
            intent, (
                    PendingIntent.FLAG_MUTABLE
                            or PendingIntent.FLAG_UPDATE_CURRENT
                    )
        )
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        logger.info { "onBeginGetCredentialRequest called" }
        
        try {
            val response = processGetCredentialRequest(request)
            if (response != null) {
                callback.onResult(response)
            } else {
                // No matching credential types found
                callback.onError(GetCredentialUnknownException("No supported credential types in request"))
            }
        } catch (e: Exception) {
            logger.error(e) { "Error processing get credential request" }
            callback.onError(GetCredentialUnknownException(e.message))
        }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        TODO("Not yet implemented")
    }

    private fun processGetCredentialRequest(request: BeginGetCredentialRequest): BeginGetCredentialResponse? {
        logger.debug { "Processing get credential request with ${request.beginGetCredentialOptions.size} options" }
        
        val callingPackage = request.callingAppInfo?.packageName
        logger.info { "Processing get credential request for package: $callingPackage" }
        
        if (callingPackage == null) {
            logger.warn { "No calling package name available" }
            return null
        }
        
        val credentialEntries = mutableListOf<CredentialEntry>()
        var hasPasswordOption = false
        var hasPublicKeyOption = false
        
        // Process each credential option type
        for (option in request.beginGetCredentialOptions) {
            when (option) {
                is BeginGetPasswordOption -> {
                    logger.debug { "Found BeginGetPasswordOption" }
                    hasPasswordOption = true
                    
                    // Create a single entry that launches the Flutter app
                    // The Flutter app will provide the actual credentials
                    val entry = createPasswordCredentialEntry(
                        packageName = callingPackage,
                        option = option
                    )
                    if (entry != null) {
                        credentialEntries.add(entry)
                    }
                }
                // Note: BeginGetPublicKeyCredentialOption would be handled here for passkeys
                else -> {
                    logger.debug { "Found unsupported option type: ${option.javaClass.simpleName}" }
                    hasPublicKeyOption = true
                }
            }
        }
        
        if (credentialEntries.isEmpty()) {
            logger.info { "No credential entries created" }
            return null
        }

        
        logger.info { "Created ${credentialEntries.size} credential entries for ${request.callingAppInfo?.packageName}" }
        
        return BeginGetCredentialResponse(credentialEntries)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createPasswordCredentialEntry(
        packageName: String,
        option: BeginGetPasswordOption
    ): PasswordCredentialEntry? {
        logger.debug { "Creating password credential entry for package: $packageName" }
        
        val activityName = try {
            this.packageManager.getApplicationInfo(this.packageName, PackageManager.GET_META_DATA).run {
                metaData.getString("com.keevault.flutter_autofill_service.GET_CREDENTIAL_ACTIVITY_NAME")
                    ?: metaData.getString("com.keevault.flutter_autofill_service.ACTIVITY_NAME")
            } ?: "com.keevault.flutter_autofill_service_example.AutofillActivity"
        } catch (e: Exception) {
            logger.error(e) { "Error getting activity name from metadata" }
            "com.keevault.flutter_autofill_service_example.AutofillActivity"
        }
        
        logger.debug { "Using activity: $activityName" }
        
        // Use getStartIntent to create the intent with proper metadata
        val intent = IntentHelpers.getStartIntent(
            activityName,
            setOf(packageName),
            setOf(),
            applicationContext,
            "/credential_manager_get_password",
            null
        )
        
        val requestCode = packageName.hashCode()
        
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Get the app label for display
        val displayName = try {
            this.packageManager.getApplicationLabel(
                this.packageManager.getApplicationInfo(this.packageName, 0)
            ).toString()
        } catch (e: Exception) {
            applicationContext.getString(android.R.string.unknownName)
        }
        
        // Create entry that shows the app name
        return PasswordCredentialEntry.Builder(
            context = applicationContext,
            username = displayName,
            pendingIntent = pendingIntent,
            beginGetPasswordOption = option
        )
            .setDisplayName(displayName)
            .build()
    }

    companion object {
        private const val CREATE_PASSWORD_REQUEST_CODE = 7365
        private const val PERSONAL_ACCOUNT_ID = "personal_account"
        private const val CREATE_PASSKEY_INTENT = "com.keevault.flutter_autofill_service.CREATE_PASSKEY"
    }

}

