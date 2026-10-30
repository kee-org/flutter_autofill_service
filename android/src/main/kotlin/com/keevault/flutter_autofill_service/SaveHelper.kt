package com.keevault.flutter_autofill_service

import android.os.Build
import android.os.Bundle
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

object SaveHelper {
    fun createSaveInfo(clientState: Bundle, autoFillIdUsernameGuessed: AutofillId?, autoFillIdPasswordGuessed: AutofillId?, autoFillIdUsernameMatched: AutofillId?, autoFillIdPasswordMatched: AutofillId?): SaveInfo? {
        if (autoFillIdPasswordGuessed == null && autoFillIdUsernameGuessed == null && autoFillIdUsernameMatched == null && autoFillIdPasswordMatched == null) {
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

        val passwordId = autoFillIdPasswordMatched ?: autoFillIdPasswordGuessed
        var usernameId = clientState.getParcelable<AutofillId>("usernameId") ?: autoFillIdUsernameMatched ?: autoFillIdUsernameGuessed
        // Android could report one field's value as both the username and the password so we keep
        // only the password and let the user supply the username when saving.
        if (usernameId != null && usernameId == passwordId) {
            logger.warn { "Username and password fields are the same field. Saving the password only." }
            clientState.remove("usernameId")
            usernameId = null
        }
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
            // This should prevent spurious save requests when we incorrectly guess that a text field is a username field and the app activity ends
            builder.setFlags(SaveInfo.FLAG_DELAY_SAVE)
        } else {
            clientState.putParcelable("passwordId", passwordId)
            // username may be null if we only found a password field and no earlier screen had a username field on it
            builder = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, arrayOf(passwordId))
                    .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
        }
        return builder.build()
    }

    /**
     * Picks the most likely username and password fields of a sign-in or registration form. The
     * two ids are never the same: the password is chosen first and cannot also be the username.
     * Only ids and heuristic details are logged, never field values.
     */
    fun guessAutofillIdsForSave(parser: AssistStructureParser): Pair<AutofillId?, AutofillId?> {
        // If both new password and existing password fields are found in the same app view, we
        // guess that the new password field is more likely to be the best one to use but real
        // world examples will be needed before we know if this is reasonable. Hopefully
        // no apps include both of these in a single view anyway!
        val passwordPick = bestField(parser, listOf(AutofillInputType.NewPassword, AutofillInputType.Password), null)
        val usernamePick = bestField(
            parser,
            listOf(AutofillInputType.NewUserName, AutofillInputType.UserName, AutofillInputType.Email),
            passwordPick?.second?.autofillId
        )
        val usernameId = usernamePick?.second?.autofillId
        val passwordId = passwordPick?.second?.autofillId
        logger.debug {
            "Save ids: usernameId=$usernameId (${describe(usernamePick)}), passwordId=$passwordId (${describe(passwordPick)})"
        }
        if (usernameId != null && usernameId == passwordId) {
            logger.warn { "Chose the same field for username and password: $usernameId" }
        }
        return Pair(usernameId, passwordId)
    }

    private fun describe(pick: Pair<AutofillInputType, MatchedField>?): String =
        pick?.let { (type, field) -> "type=$type, heuristic='${field.heuristic.message}', weight=${field.heuristic.weight}" } ?: "none"

    /**
     * The highest weighted field of the first of [types] that has any candidates, ignoring [excluding].
     * When weights tie, visible fields of the primary web host win over the others, which still count as
     * a fallback (e.g. a hidden username field on the password step of a multi-step sign in).
     */
    private fun bestField(
        parser: AssistStructureParser,
        types: List<AutofillInputType>,
        excluding: AutofillId?
    ): Pair<AutofillInputType, MatchedField>? {
        for (type in types) {
            val best = parser.fieldIds[type].orEmpty()
                .filter { it.autofillId != excluding }
                .maxWithOrNull(compareBy<MatchedField>({ it.heuristic.weight }, { if (it.isVisible && it.webHost == parser.primaryHost) 1 else 0 }))
            if (best != null) return type to best
        }
        return null
    }
}
