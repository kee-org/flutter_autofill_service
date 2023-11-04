package com.keevault.flutter_autofill_service

import android.os.Build
import android.os.Bundle
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

object SaveHelper {
    fun createSaveInfo(clientState: Bundle, autoFillIdUsernameGuessed: AutofillId?, autoFillIdPasswordGuessed: AutofillId?, autoFillIdUsernameMatched: AutofillId?, autoFillIdPasswordMatched: AutofillId?): SaveInfo? {
        if (autoFillIdPasswordGuessed == null && autoFillIdUsernameGuessed == null && autoFillIdUsernameMatched == null) {
            return null
        }

        // AutoFillIds from the full entry matching lookup process take precedence over those that we guessed with no
        // additional context but we may be able to improve those guesses in future so we log that situation for
        // use in any future specific diagnosis operation
        if (autoFillIdUsernameMatched?.let { autoFillIdUsernameGuessed != null && it != autoFillIdUsernameGuessed } == true) {
            logger.warn { "AutofillId of matched entry username differs from our guessed ID" }
        }
        if (autoFillIdPasswordMatched?.let { autoFillIdPasswordGuessed != null && it != autoFillIdPasswordGuessed } == true) {
            logger.warn { "AutofillId of matched entry password differs from our guessed ID" }
        }

        val usernameId = clientState.getParcelable("usernameId") ?: autoFillIdUsernameMatched ?: autoFillIdUsernameGuessed
        val passwordId = autoFillIdPasswordMatched ?: autoFillIdPasswordGuessed
        val builder: SaveInfo.Builder

        if (usernameId != null && passwordId != null) {
            clientState.putParcelable("usernameId", usernameId)
            clientState.putParcelable("passwordId", passwordId)
            // Android docs say that we should select only SaveInfo.SAVE_DATA_TYPE_PASSWORD in this
            // case since that simplifies the message displayed to the user. That is wrong. Failing
            // to include the USERNAME flag also results in the username being ommitted from the
            // SaveInfo data supplied to us.
            builder = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD, arrayOf(usernameId, passwordId))
                    .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
        } else if (usernameId != null) {
            clientState.putParcelable("usernameId", usernameId)
            builder = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME, arrayOf(usernameId))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // This should prevent spurious save requests when we incorrectly guess that a text field is a username field and the app activity ends
                builder.setFlags(SaveInfo.FLAG_DELAY_SAVE)
            }
        } else {
            clientState.putParcelable("passwordId", passwordId)
            // username may be null if we only found a password field and no earlier screen had a username field on it
            builder = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, arrayOf(passwordId))
                    .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
        }
        return builder.build()
    }

    fun guessAutofillIdsForSave(parser: AssistStructureParser): Pair<AutofillId?, AutofillId?> {
        // If both new password and existing password fields are found in the same app view, we 
        // guess that the new password field is more likely to be the best one to use but real
        // world examples will be needed before we know if this is reasonable. Hopefully
        // no apps include both of these in a single view anyway!
        val usernameId = parser.fieldIds[AutofillInputType.NewUserName]?.maxByOrNull { it.heuristic.weight }?.autofillId
            ?: parser.fieldIds[AutofillInputType.UserName]?.maxByOrNull { it.heuristic.weight }?.autofillId
            ?: parser.fieldIds[AutofillInputType.Email]?.maxByOrNull { it.heuristic.weight }?.autofillId
        val passwordId = parser.fieldIds[AutofillInputType.NewPassword]?.maxByOrNull { it.heuristic.weight }?.autofillId
            ?: parser.fieldIds[AutofillInputType.Password]?.maxByOrNull { it.heuristic.weight }?.autofillId
        return Pair(usernameId, passwordId)

    }
}