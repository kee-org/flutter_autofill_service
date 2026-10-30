package com.keevault.flutter_autofill_service

import android.app.assist.AssistStructure
import android.content.ComponentName
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * `TYPE_TEXT_VARIATION_*` values are enumerated values and not bit flags, so a field with one
 * variation must not be classified as a field with another. If it is, the same field can end up
 * chosen as both the username and the password of a save request.
 */
class SaveHelperTest {

    private companion object {
        const val TEXT = InputType.TYPE_CLASS_TEXT
        const val PASSWORD = TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD // 0x81
        const val VISIBLE_PASSWORD = TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD // 0x91
        const val WEB_EDIT_TEXT = TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT // 0xa1
        const val WEB_EMAIL = TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS // 0xd1
        const val WEB_PASSWORD = TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD // 0xe1
        const val EMAIL = TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS // 0x21
        const val PERSON_NAME = TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME // 0x61
        const val PLAIN = TEXT // 0x01
    }

    /** A text input field. Gets an autofill ID unless it is the root or a plain container. */
    private class Node(
        val inputType: Int = PLAIN,
        val visibility: Int = View.VISIBLE,
        val webDomain: String? = null,
        val isField: Boolean = true,
        val children: List<Node> = emptyList(),
    ) {
        val autofillId: AutofillId? = if (isField) mock(AutofillId::class.java) else null
    }

    private val context: Context = mock(Context::class.java)

    private fun viewNode(spec: Node): AssistStructure.ViewNode {
        val children = spec.children.map { viewNode(it) }
        return mock(AssistStructure.ViewNode::class.java).also { n ->
            `when`(n.webDomain).thenReturn(spec.webDomain)
            `when`(n.webScheme).thenReturn(if (spec.webDomain != null) "https" else null)
            `when`(n.visibility).thenReturn(spec.visibility)
            `when`(n.width).thenReturn(100)
            `when`(n.height).thenReturn(40)
            `when`(n.childCount).thenReturn(children.size)
            children.forEachIndexed { i, child -> `when`(n.getChildAt(i)).thenReturn(child) }
            if (spec.isField) {
                `when`(n.autofillId).thenReturn(spec.autofillId)
                `when`(n.autofillType).thenReturn(View.AUTOFILL_TYPE_TEXT)
                `when`(n.inputType).thenReturn(spec.inputType)
            }
        }
    }

    private fun parserOf(vararg fields: Node): AssistStructureParser {
        val rootViewNode = viewNode(Node(isField = false, children = fields.toList()))
        val window = mock(AssistStructure.WindowNode::class.java).also {
            `when`(it.rootViewNode).thenReturn(rootViewNode)
        }
        val component = mock(ComponentName::class.java).also {
            `when`(it.packageName).thenReturn("com.example.app")
        }
        val structure = mock(AssistStructure::class.java).also {
            `when`(it.windowNodeCount).thenReturn(1)
            `when`(it.getWindowNodeAt(0)).thenReturn(window)
            `when`(it.activityComponent).thenReturn(component)
        }
        return AssistStructureParser(structure, context, 31)
    }

    /** The types of field that the heuristics consider a field with this [inputType] to be. */
    private fun classify(inputType: Int): Set<AutofillInputType> {
        val parser = parserOf(Node(inputType = inputType))
        return parser.fieldIds.filterValues { it.isNotEmpty() }.keys
    }

    // --- heuristic classification ---

    @Test
    fun textPasswordIsOnlyAPassword() {
        assertEquals(setOf(AutofillInputType.Password, AutofillInputType.NewPassword), classify(PASSWORD))
    }

    @Test
    fun visiblePasswordIsOnlyAPassword() {
        assertEquals(setOf(AutofillInputType.Password, AutofillInputType.NewPassword), classify(VISIBLE_PASSWORD))
    }

    @Test
    fun webEditTextIsNeitherAPasswordNorAnEmail() {
        assertEquals(emptySet<AutofillInputType>(), classify(WEB_EDIT_TEXT))
    }

    @Test
    fun webEmailIsOnlyAnEmail() {
        assertEquals(setOf(AutofillInputType.Email), classify(WEB_EMAIL))
    }

    @Test
    fun webPasswordIsOnlyAPassword() {
        assertEquals(setOf(AutofillInputType.Password, AutofillInputType.NewPassword), classify(WEB_PASSWORD))
    }

    @Test
    fun emailIsOnlyAnEmail() {
        assertEquals(setOf(AutofillInputType.Email), classify(EMAIL))
    }

    @Test
    fun personNameIsNeitherAPasswordNorAnEmail() {
        assertEquals(emptySet<AutofillInputType>(), classify(PERSON_NAME))
    }

    @Test
    fun variationOfAnotherInputClassIsIgnored() {
        // TYPE_CLASS_NUMBER (2) with a variation value of 0x80 is not a text password
        assertEquals(emptySet<AutofillInputType>(), classify(InputType.TYPE_CLASS_NUMBER or 0x80))
    }

    @Test
    fun hasTextVariationComparesTheWholeVariation() {
        assertTrue(PASSWORD.hasTextVariation(InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(WEB_EMAIL.hasTextVariation(InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(WEB_PASSWORD.hasTextVariation(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(WEB_EDIT_TEXT.hasTextVariation(InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(null.hasTextVariation(InputType.TYPE_TEXT_VARIATION_PASSWORD))
        // flags other than the class and variation (e.g. no suggestions, cap words) are ignored
        assertTrue((PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).hasTextVariation(InputType.TYPE_TEXT_VARIATION_PASSWORD))
    }

    // --- guessAutofillIdsForSave ---

    @Test
    fun emailFieldBeforePasswordFieldGivesDistinctIds() {
        val email = Node(inputType = WEB_EMAIL)
        val password = Node(inputType = PASSWORD)

        val (usernameId, passwordId) = SaveHelper.guessAutofillIdsForSave(parserOf(email, password))

        assertSame(email.autofillId, usernameId)
        assertSame(password.autofillId, passwordId)
    }

    @Test
    fun webPasswordFieldIsNotAlsoTheUsername() {
        val password = Node(inputType = WEB_PASSWORD)
        val other = Node(inputType = PLAIN)

        val (usernameId, passwordId) = SaveHelper.guessAutofillIdsForSave(parserOf(other, password))

        assertSame(password.autofillId, passwordId)
        assertNull(usernameId)
    }

    @Test
    fun usernameIsNeverTheSameFieldAsThePassword() {
        val combinations = listOf(WEB_EMAIL, WEB_PASSWORD, PASSWORD, VISIBLE_PASSWORD, WEB_EDIT_TEXT, EMAIL, PERSON_NAME, PLAIN)
        for (first in combinations) {
            for (second in combinations) {
                val (usernameId, passwordId) = SaveHelper.guessAutofillIdsForSave(
                    parserOf(Node(inputType = first), Node(inputType = second))
                )
                if (usernameId != null) {
                    assertNotEquals("inputTypes $first, $second", usernameId, passwordId)
                }
            }
        }
    }

    @Test
    fun onlyAPasswordFieldIsChosenAsThePasswordAlone() {
        val password = Node(inputType = PASSWORD)

        val (usernameId, passwordId) = SaveHelper.guessAutofillIdsForSave(parserOf(password))

        assertNull(usernameId)
        assertSame(password.autofillId, passwordId)
    }

    @Test
    fun hiddenEmailFieldOnPasswordStepIsStillTheUsername() {
        val hiddenEmail = Node(inputType = EMAIL, visibility = View.INVISIBLE)
        val password = Node(inputType = PASSWORD)

        val (usernameId, passwordId) = SaveHelper.guessAutofillIdsForSave(parserOf(hiddenEmail, password))

        assertSame(hiddenEmail.autofillId, usernameId)
        assertSame(password.autofillId, passwordId)
    }

    @Test
    fun visibleFieldWinsAWeightTie() {
        val hiddenEmail = Node(inputType = EMAIL, visibility = View.INVISIBLE)
        val visibleEmail = Node(inputType = EMAIL)
        val password = Node(inputType = PASSWORD)

        val (usernameId, _) = SaveHelper.guessAutofillIdsForSave(parserOf(hiddenEmail, visibleEmail, password))

        assertSame(visibleEmail.autofillId, usernameId)
    }

    @Test
    fun fieldOfAnotherWebDomainLosesAWeightTie() {
        val web = Node(webDomain = "bank.example", isField = false, children = listOf(
            Node(inputType = EMAIL), // the focus/claimed domain is decided by the password field's host
            Node(inputType = PASSWORD),
        ))
        val native = Node(inputType = EMAIL)

        // The native email field comes first but belongs to no web domain
        val parser = parserOf(native, web)
        val (usernameId, passwordId) = SaveHelper.guessAutofillIdsForSave(parser)

        assertNotNull(passwordId)
        assertSame(web.children[0].autofillId, usernameId)
        assertSame(web.children[1].autofillId, passwordId)
    }
}
