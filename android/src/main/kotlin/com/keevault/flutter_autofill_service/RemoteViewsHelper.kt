package com.keevault.flutter_autofill_service

import android.graphics.drawable.Icon
import android.widget.RemoteViews
import androidx.annotation.DrawableRes

/**
 * This is a class containing helper methods for building Autofill Datasets and Responses.
 */
object RemoteViewsHelper {

    fun viewsWithAuth(packageName: String, text: String,
                      @DrawableRes drawableId: Int = R.drawable.ic_lock_24dp): RemoteViews {
        return simpleRemoteViews(packageName, text, drawableId)
    }

    fun viewsWithNoAuth(packageName: String, text: String,
                        @DrawableRes drawableId: Int = R.drawable.ic_person_24dp): RemoteViews {
        return simpleRemoteViews(packageName, text, drawableId)
    }

    fun viewsWithNoAuthOptionalIcon(packageName: String, text: String, @DrawableRes drawableId: Int?): RemoteViews {
        if (drawableId != null) {
            return simpleRemoteViews(packageName, text, drawableId)
        }
        val presentation = RemoteViews(
            packageName,
            android.R.layout.simple_list_item_1
        ).apply {
            setTextViewText(android.R.id.text1, text)
        }
        return presentation
    }

    private fun simpleRemoteViews(
        packageName: String, remoteViewsText: String,
        @DrawableRes drawableId: Int
    ): RemoteViews {
        val presentation = RemoteViews(
            packageName,
            R.layout.multidataset_service_list_item
        )
        presentation.setTextViewText(R.id.text, remoteViewsText)
        val icon = Icon.createWithResource(packageName, drawableId)
        presentation.setImageViewIcon(R.id.icon, icon)
        return presentation
    }
}