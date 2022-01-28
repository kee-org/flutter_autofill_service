package com.keevault.flutter_autofill_service

import android.content.Context
import android.content.Intent

object IntentHelpers {
    fun getStartIntent(activityName: String, packageNames: Set<String>, webDomains: Set<WebDomain>, context: Context, autofillMode: String, saveInfo: SaveInfoMetadata?): Intent {
        val startIntent = Intent()
        startIntent.setClassName(context, activityName)
        startIntent.putExtra("autofill_mode", autofillMode)

        // We serialize to string, because custom Parcelable classes can't be used for cross-app messages
        // https://stackoverflow.com/a/39478479/109219
        startIntent.putExtra(
                AutofillMetadata.EXTRA_NAME,
                AutofillMetadata(packageNames, webDomains, saveInfo).toJsonString()
        )

        // Note: Do not make a pending intent immutable by using PendingIntent.FLAG_IMMUTABLE
        // as the platform needs to fill in the authentication arguments.

        return startIntent
    }
}