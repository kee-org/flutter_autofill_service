package com.keevault.flutter_autofill_service

import android.content.IntentSender
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.view.autofill.AutofillId
import android.widget.RemoteViews

/**
 * Builds the locked dataset used by the in-app selection flow.
 *
 * The dataset holds no values, only an "unlock" presentation for every field. Tapping it launches
 * the authentication intent and the dataset returned by that activity is filled immediately.
 */
object UnlockDatasetHelper {

    /**
     * Returns null if the dataset could not be built (for example when [fieldIds] is empty).
     */
    fun build(
        fieldIds: Collection<AutofillId>,
        menu: RemoteViews,
        inline: InlinePresentation?,
        intentSender: IntentSender,
    ): Dataset? = try {
        Dataset.Builder().apply {
            fieldIds.forEach { id ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val presentations = Presentations.Builder().setMenuPresentation(menu).apply {
                        if (inline != null) setInlinePresentation(inline)
                    }.build()
                    setField(id, Field.Builder().setPresentations(presentations).build())
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && inline != null) {
                    setValue(id, null, menu, inline)
                } else {
                    setValue(id, null, menu)
                }
            }
            setId("unlock")
            setAuthentication(intentSender)
        }.build()
    } catch (e: IllegalStateException) {
        null
    }
}
