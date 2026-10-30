package com.keevault.flutter_autofill_service

import android.content.Context
import android.content.Intent

object IntentHelpers {
    // PendingIntent identity ignores extras, so each distinct launch needs its own request code.
    const val REQUEST_CODE_UNLOCK = 1230 // service: initial unlock
    const val REQUEST_CODE_SELECT_ANOTHER = 1231 // plugin: "Use a different entry"

    fun getStartIntent(activityName: String, appId: String?, appIdSource: AppIdSource, webDomains: Set<WebDomain>, context: Context, autofillMode: String, saveInfo: SaveInfoMetadata?, isWebOrigin: Boolean = false, appIsBrowser: Boolean = false): Intent {
        val startIntent = Intent()
        startIntent.setClassName(context, activityName)
        startIntent.putExtra("autofill_mode", autofillMode)

        // We serialize to string, because custom Parcelable classes can't be used for cross-app messages
        // https://stackoverflow.com/a/39478479/109219
        startIntent.putExtra(
                AutofillMetadata.EXTRA_NAME,
                AutofillMetadata(appId = appId, appIdSource = appIdSource, webDomains = webDomains, isWebOrigin = isWebOrigin, appIsBrowser = appIsBrowser, saveInfo = saveInfo).toJsonString()
        )

        // Make the intent explicit to allow mutability
        startIntent.setPackage(context.packageName)

        // Note: Do not make a pending intent immutable by using PendingIntent.FLAG_IMMUTABLE
        // as the platform needs to fill in the authentication arguments.

        return startIntent
    }
}
