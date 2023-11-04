package com.keevault.flutter_autofill_service

import android.content.*
import androidx.core.content.edit
import com.squareup.moshi.*
import io.github.oshai.kotlinlogging.KotlinLogging

@JsonClass(generateAdapter = true)
data class AutofillPreferences(
        val enableDebug: Boolean = false,
        val enableSaving: Boolean = true,
        val enableIMERequests: Boolean = true
) {

    companion object {
        private const val PREF_JSON_NAME = "AutofillPreferences"

        private val moshi = Moshi.Builder()
            .build() as Moshi
        private val jsonAdapter get() =
            requireNotNull(moshi.adapter(AutofillPreferences::class.java))

        fun fromPreferences(prefs: SharedPreferences): AutofillPreferences =
            prefs.getString(PREF_JSON_NAME, null)?.let(Companion::fromJsonString)
                ?: AutofillPreferences()

        private fun fromJsonString(jsonString: String) =
            jsonAdapter.fromJson(jsonString)

        fun fromJsonValue(data: Map<String, Any>): AutofillPreferences? =
            jsonAdapter.fromJsonValue(data)
    }

    fun saveToPreferences(prefs: SharedPreferences) {
        prefs.edit {
            putString(PREF_JSON_NAME, toJson())
        }
    }

    fun toJsonValue(): Any? =
        jsonAdapter.toJsonValue(this)

    private fun toJson(): String = jsonAdapter.toJson(this)
}

class AutofillPreferenceStore private constructor(private val prefs: SharedPreferences) {


    companion object {

        private const val SHARED_PREFS_NAME = "com.keevault.flutter_autofill_service.prefs"

        private val lock = Any()
        private var instance: AutofillPreferenceStore? = null

        fun getInstance(context: Context): AutofillPreferenceStore =
            instance ?: getInstance(context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE))

        private fun getInstance(prefs: SharedPreferences): AutofillPreferenceStore {
            synchronized(lock) {
                return instance ?: {
                    // We can't log properly becauase logger doesn't permit reconfiguration after
                    // the first message is logged and we need to look up some configuration in the pref store
                    println { "Creating new AutofillPreferenceStore." }
                    val ret = AutofillPreferenceStore(prefs)
                    instance = ret
                    ret
                }()
            }
        }

    }

    var autofillPreferences: AutofillPreferences = AutofillPreferences.fromPreferences(prefs)
        set(value) {
            field = value
            value.saveToPreferences(prefs)
        }
}