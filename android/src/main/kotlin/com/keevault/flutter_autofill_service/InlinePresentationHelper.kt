package com.keevault.flutter_autofill_service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.slice.Slice
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.autofill.InlinePresentation
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi

@RequiresApi(api = Build.VERSION_CODES.R)
object InlinePresentationHelper {

    fun viewsWithAuth(text: String,
                      inlinePresentationSpec: InlinePresentationSpec,
                      pendingIntent: PendingIntent,
                      context: Context): InlinePresentation? {
        if (Build.VERSION.SDK_INT < 30) {
            return null
        }
        val slice = createSlice(inlinePresentationSpec, text, null, R.drawable.ic_lock_24dp, pendingIntent, context)
        return if (slice != null) {
            InlinePresentation(slice, inlinePresentationSpec, false)
        } else null
    }

    fun viewsWithNoAuth(text: String,
                        inlinePresentationSpec: InlinePresentationSpec,
                        pendingIntent: PendingIntent?,
                        context: Context): InlinePresentation? {
        if (Build.VERSION.SDK_INT < 30) {
            return null
        }

        // Not sure why but InlinePresentation API requires a pending intent so we include a dummy one if none was supplied to us
        val slice = createSlice(inlinePresentationSpec, text, null, R.drawable.ic_person_24dp, pendingIntent
                ?: PendingIntent.getService(context, 0, Intent(),
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT), context)
        return if (slice != null) {
            InlinePresentation(slice, inlinePresentationSpec, false)
        } else null
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
        presentation.setImageViewResource(R.id.icon, drawableId)
        return presentation
    }

    // .slice is apparently restricted, but all docs say it must be used so this lint is presumably 
    // required due to an Android bug. Also per-line lint exceptions are not supported for some 
    // reason so we have to take the risk on the entire method being ignored.
    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun createSlice(
            inlinePresentationSpec: InlinePresentationSpec,
            title: String,
            subtitle: String?,
            iconId: Int,
            pendingIntent: PendingIntent,
            context: Context
    ): Slice? {
        // Make sure that the IME spec claims support for v1 UI template.
        val imeStyle = inlinePresentationSpec.style
        if (!UiVersions.getVersions(imeStyle).contains(UiVersions.INLINE_UI_VERSION_1)) {
            return null
        }

        // Build the content for the v1 UI.
        val builder = InlineSuggestionUi.newContentBuilder(pendingIntent)
        if (title.isNotEmpty()) {
            builder.setTitle(title)
        }
        if (subtitle?.isNotEmpty() == true) {
            builder.setSubtitle(subtitle)
        }
        if (iconId > 0) {
            val icon = Icon.createWithResource(context, iconId)
            if (icon != null) {
                //TODO: If want to avoid tinting some icons such as favicons, logos, etc. we can use the line below
                //icon.SetTintBlendMode(BlendMode.Dst);
                builder.setStartIcon(icon)
            }
        }
        return builder.build().slice
    }
}