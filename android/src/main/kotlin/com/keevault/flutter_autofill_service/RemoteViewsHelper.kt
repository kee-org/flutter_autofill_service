package com.keevault.flutter_autofill_service

import android.graphics.drawable.Icon
import android.widget.RemoteViews
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

/**
 * This is a class containing helper methods for building Autofill Datasets and Responses.
 */
object RemoteViewsHelper {

    fun viewsWithAuth(packageName: String, text: String,
                      @DrawableRes drawableId: Int = R.drawable.ic_lock_24dp): RemoteViews {
        return simpleRemoteViews(packageName, text, drawableId)
    }

    /**
     * The unlock button shown in the Android 13+ fill dialog. Uses its own layout (a filled button)
     * rather than the dropdown row. The icon is tinted with [iconTint]; pass the same colour as the
     * text so the whole button reads as one foreground colour.
     * Requires API 23+ (Icon.setTint). Callers are already on API 33+.
     */
    fun fillDialogUnlock(
        packageName: String,
        text: String,
        @DrawableRes drawableId: Int,
        @ColorInt iconTint: Int,
    ): RemoteViews {
        return RemoteViews(packageName, R.layout.fill_dialog_unlock_item).apply {
            setTextViewText(R.id.text, text)
            setImageViewIcon(
                R.id.icon,
                Icon.createWithResource(packageName, drawableId).setTint(iconTint)
            )
        }
    }

    /**
     * Subtitle shown above the fill dialog's unlock button (FillResponse.Builder.setDialogHeader).
     * Requires API 33+. Callers must be inside a TIRAMISU guard.
     */
    fun fillDialogHeader(packageName: String, text: String): RemoteViews {
        return RemoteViews(packageName, R.layout.fill_dialog_header).apply {
            setTextViewText(R.id.text, text)
        }
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