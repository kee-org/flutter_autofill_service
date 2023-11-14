package com.keevault.flutter_autofill_service

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.webauthn.PublicKeyCredentialCreationOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import org.tinylog.Level
import org.tinylog.policies.DynamicPolicy
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

class FlutterCredentialProviderService : CredentialProviderService() {
    @RequiresApi(Build.VERSION_CODES.S)
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
                // Request is passkey type
                return handleCreatePasskeyQuery(request)
            }
        }
        // Request not supported
        return null
    }

    private fun handleCreatePasskeyQuery(
        request: BeginCreatePublicKeyCredentialRequest
    ): BeginCreateCredentialResponse {

        logger.info { "handleCreatePasskeyQuery" }

        //TODO: Think we just need one...
        // Adding two create entries - one for storing credentials to the 'Personal'
        // account, and one for storing them to the 'Family' account. These
        // accounts are local to this sample app only.
        val createEntries: MutableList<CreateEntry> = mutableListOf()
//        createEntries.add( CreateEntry(
//            PERSONAL_ACCOUNT_ID,
//            createNewPendingIntent(PERSONAL_ACCOUNT_ID, CREATE_PASSKEY_INTENT)
//        ))


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

    private fun createNewPendingIntent(accountId: String, action: String): PendingIntent {
        //TODO: Try sending this to the MainActivity and listending for the intent and then
        // log it arrived (later can actually make flutter do something)
        // onNewIntent() method
        val intent = Intent(action).setPackage(packageName)

        //TODO: The corresponding Activity should be set up to surface any required Biometric prompt, confirmation or selection required.

        //TODO: Not needed I think cos we have just one "account" - the primary Kee Vault
        // Add your local account ID as an extra to the intent, so that when
        // user selects this entry, the credential can be saved to this
        // account
        //intent.putExtra(EXTRA_KEY_ACCOUNT_ID, accountId)

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
        TODO("Not yet implemented")
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        TODO("Not yet implemented")
    }
//
//    fun handleCreateCredentialIntent(intent: Intent) {
//        val request =
//            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
//
//        val accountId = intent.getStringExtra(CredentialsRepo.EXTRA_KEY_ACCOUNT_ID)
//        if (request != null && request.callingRequest is CreatePublicKeyCredentialRequest) {
//            val publicKeyRequest: CreatePublicKeyCredentialRequest =
//                request.callingRequest as CreatePublicKeyCredentialRequest
//            createPasskey(
//                publicKeyRequest.requestJson,
//                request.callingAppInfo,
//                publicKeyRequest.clientDataHash,
//                accountId
//            )
//        }
//
//    }
//
//    //TODO: Need to send this to Flutter instead. Just send the Response from kotlin?
//    fun createPasskey(
//        requestJson: String,
//        callingAppInfo: CallingAppInfo,
//        clientDataHash: ByteArray?,
//        accountId: String?
//    ) {
//        val request = PublicKeyCredentialCreationOptions(requestJson)
//
//                // Generate a credentialId
//                val credentialId = ByteArray(32)
//                SecureRandom().nextBytes(credentialId)
//
//                // Generate a credential key pair
//                val spec = ECGenParameterSpec("secp256r1")
//                val keyPairGen = KeyPairGenerator.getInstance("EC");
//                keyPairGen.initialize(spec)
//                val keyPair = keyPairGen.genKeyPair()
//
//                // Save passkey in your database as per your own implementation
//
//                // Create AuthenticatorAttestationResponse object to pass to
//                // FidoPublicKeyCredential
//
//                val response = AuthenticatorAttestationResponse(
//                    requestOptions = request,
//                    credentialId = credentialId,
//                    credentialPublicKey = getPublicKeyFromKeyPair(keyPair),
//                    origin = appInfoToOrigin(callingAppInfo),
//                    up = true,
//                    uv = true,
//                    be = true,
//                    bs = true,
//                    packageName = callingAppInfo.packageName
//                )
//
//                val credential = FidoPublicKeyCredential(
//                    rawId = credentialId, response = response
//                )
//                val result = Intent()
//
//                val createPublicKeyCredResponse =
//                    CreatePublicKeyCredentialResponse(credential.json())
//
//                // Set the CreateCredentialResponse as the result of the Activity
//                PendingIntentHandler.setCreateCredentialResponse(
//                    result, createPublicKeyCredResponse
//                )
//                setResult(Activity.RESULT_OK, result)
//                finish()
//
//    }
//
//    fun appInfoToOrigin(info: CallingAppInfo): String {
//        val cert = info.signingInfo.apkContentsSigners[0].toByteArray()
//        val md = MessageDigest.getInstance("SHA-256");
//        val certHash = md.digest(cert)
//        // This is the format for origin
//        return "android:apk-key-hash:${b64Encode(certHash)}"
//    }

}

