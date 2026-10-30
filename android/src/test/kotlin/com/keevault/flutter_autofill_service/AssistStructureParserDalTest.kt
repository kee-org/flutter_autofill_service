package com.keevault.flutter_autofill_service

import android.app.assist.AssistStructure
import android.content.ComponentName
import android.content.Context
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.view.View
import android.view.autofill.AutofillId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * The app being filled controls every string in the AssistStructure except `activityComponent`
 * (for example `ViewStructure.setId(id, packageName, ...)` and `setWebDomain()` accept anything).
 * These tests check that nothing it provides can make a domain look verified, that each claimed
 * domain is judged on its own, and that credentials only go to visible fields of the focused domain.
 */
class AssistStructureParserDalTest {

    private companion object {
        const val S = 31
        const val R = 30
        const val BANK = "bank.example"
        const val EVIL = "evil.example"
        const val VERIFIED = DomainVerificationUserState.DOMAIN_STATE_VERIFIED
    }

    /** Describes one view node of a synthetic structure. A node with [field] set is a credential field. */
    private class Node(
        val idPackage: String? = null,
        val webDomain: String? = null,
        val field: String? = null, // "password" or "username"
        val focused: Boolean = false,
        val visibility: Int = View.VISIBLE,
        val width: Int = 100,
        val height: Int = 40,
        val children: List<Node> = emptyList(),
    ) {
        val autofillId: AutofillId? = if (field != null) mock(AutofillId::class.java) else null
    }

    private class Fixture {
        val manager: DomainVerificationManager = mock(DomainVerificationManager::class.java)
        val context: Context = mock(Context::class.java).also {
            `when`(it.getSystemService(Context.DOMAIN_VERIFICATION_SERVICE)).thenReturn(manager)
        }

        /** Declares what Android reports for [pkg]'s links: host -> DOMAIN_STATE_* */
        fun domainStates(pkg: String, states: Map<String, Int>) {
            val userState = mock(DomainVerificationUserState::class.java)
            `when`(userState.hostToStateMap).thenReturn(states)
            `when`(manager.getDomainVerificationUserState(pkg)).thenReturn(userState)
        }

        private fun viewNode(spec: Node): AssistStructure.ViewNode {
            val children = spec.children.map { viewNode(it) }
            return mock(AssistStructure.ViewNode::class.java).also { n ->
                `when`(n.idPackage).thenReturn(spec.idPackage)
                `when`(n.webDomain).thenReturn(spec.webDomain)
                `when`(n.webScheme).thenReturn(if (spec.webDomain != null) "https" else null)
                `when`(n.visibility).thenReturn(spec.visibility)
                `when`(n.width).thenReturn(spec.width)
                `when`(n.height).thenReturn(spec.height)
                `when`(n.isFocused).thenReturn(spec.focused)
                `when`(n.childCount).thenReturn(children.size)
                children.forEachIndexed { i, child -> `when`(n.getChildAt(i)).thenReturn(child) }
                if (spec.field != null) {
                    `when`(n.autofillId).thenReturn(spec.autofillId)
                    `when`(n.autofillType).thenReturn(View.AUTOFILL_TYPE_TEXT)
                    `when`(n.autofillHints).thenReturn(arrayOf(spec.field))
                }
            }
        }

        /** [componentPackage] is what the system reports as the activity being filled. */
        fun parser(componentPackage: String?, root: Node, sdkInt: Int = S): AssistStructureParser {
            val rootViewNode = viewNode(root) // built first: Mockito can't stub inside a thenReturn()
            val window = mock(AssistStructure.WindowNode::class.java).also {
                `when`(it.rootViewNode).thenReturn(rootViewNode)
            }
            val structure = mock(AssistStructure::class.java).also {
                `when`(it.windowNodeCount).thenReturn(1)
                `when`(it.getWindowNodeAt(0)).thenReturn(window)
                if (componentPackage != null) {
                    val component = mock(ComponentName::class.java)
                    `when`(component.packageName).thenReturn(componentPackage)
                    `when`(it.activityComponent).thenReturn(component)
                }
            }
            return AssistStructureParser(structure, context, sdkInt)
        }

        /** One node per entry in [idPackages], each claiming [BANK]. */
        fun bankClaims(componentPackage: String?, idPackages: List<String>, sdkInt: Int = S) =
            parser(componentPackage, Node(children = idPackages.map { Node(idPackage = it, webDomain = BANK) }), sdkInt)
    }

    private fun AssistStructureParser.domain(name: String) = webDomains.single { it.domain == name }

    private fun AssistStructureParser.fillableIds() = fillableFields().map { it.second.autofillId }.toSet()

    // --- H-1: package names chosen by the app being filled must not influence verification ---

    @Test
    fun forgedBrowserIdPackageDoesNotVerifyNonBrowserApp() {
        val f = Fixture()
        val parser = f.bankClaims("com.evil", listOf("com.android.chrome"))

        assertTrue(parser.isWebOrigin)
        assertFalse(parser.domain(BANK).dalVerified)
    }

    @Test
    fun forgedIdPackageOfAppWithVerifiedLinksDoesNotVerifyOtherApp() {
        val f = Fixture()
        f.domainStates("com.bank.app", mapOf(BANK to VERIFIED))
        f.domainStates("com.evil", mapOf(EVIL to VERIFIED))
        val parser = f.bankClaims("com.evil", listOf("com.bank.app"))

        assertFalse(parser.domain(BANK).dalVerified)
        verify(f.manager, never()).getDomainVerificationUserState("com.bank.app")
    }

    @Test
    fun forgedBrowserIdPackageDoesNotVerifyWhenThereIsNoComponent() {
        val f = Fixture()
        val parser = f.bankClaims(null, listOf("com.android.chrome"))

        assertTrue(parser.isWebOrigin)
        assertFalse(parser.domain(BANK).dalVerified)
    }

    @Test
    fun forgedIdPackageOfAppWithVerifiedLinksDoesNotVerifyWhenThereIsNoComponent() {
        val f = Fixture()
        f.domainStates("com.bank.app", mapOf(BANK to VERIFIED))
        val parser = f.bankClaims(null, listOf("com.bank.app"))

        assertFalse(parser.domain(BANK).dalVerified)
        verify(f.manager, never()).getDomainVerificationUserState("com.bank.app")
    }

    @Test
    fun excludedComponentIsTreatedAsNoComponent() {
        val f = Fixture()
        val parser = f.bankClaims("android", listOf("com.android.chrome"))

        assertFalse(parser.domain(BANK).dalVerified)
    }

    @Test
    fun forgedBrowserIdPackageDoesNotPreventWebDomainStrippingBeforeAndroid12() {
        val f = Fixture()
        val parser = f.bankClaims("com.evil", listOf("com.android.chrome"), R)

        assertTrue(parser.isWebOrigin)
        assertTrue(parser.webDomains.isEmpty())
    }

    @Test
    fun browserComponentVerifiesItsDomains() {
        val f = Fixture()
        val parser = f.bankClaims("com.android.chrome", listOf("com.android.chrome"))

        assertTrue(parser.domain(BANK).dalVerified)
    }

    @Test
    fun browserComponentVerifiesItsDomainsBeforeAndroid12() {
        val f = Fixture()
        val parser = f.bankClaims("com.android.chrome", listOf("com.android.chrome"), R)

        assertTrue(parser.domain(BANK).dalVerified)
    }

    @Test
    fun componentWithVerifiedLinksIsVerified() {
        val f = Fixture()
        f.domainStates("com.bank.app", mapOf(BANK to VERIFIED))
        val parser = f.bankClaims("com.bank.app", listOf("com.bank.app"))

        assertTrue(parser.domain(BANK).dalVerified)
    }

    @Test
    fun componentWithoutVerifiedLinksForTheDomainIsNotVerified() {
        val f = Fixture()
        f.domainStates("com.evil", mapOf(BANK to DomainVerificationUserState.DOMAIN_STATE_NONE))
        val parser = f.bankClaims("com.evil", listOf("com.evil"))

        assertFalse(parser.domain(BANK).dalVerified)
    }

    // --- H-2: every claimed domain is verified on its own ---

    @Test
    fun verifiedDomainDoesNotVerifyOtherClaimedDomains() {
        val f = Fixture()
        f.domainStates("com.evil", mapOf(EVIL to VERIFIED))
        val parser = f.parser(
            "com.evil",
            Node(children = listOf(Node(webDomain = EVIL), Node(webDomain = BANK)))
        )

        assertEquals(setOf(EVIL, BANK), parser.webDomains.map { it.domain }.toSet())
        assertTrue(parser.domain(EVIL).dalVerified)
        assertFalse(parser.domain(BANK).dalVerified)
    }

    @Test
    fun browserComponentVerifiesEveryDomain() {
        val f = Fixture()
        val parser = f.parser(
            "com.android.chrome",
            Node(children = listOf(Node(webDomain = EVIL), Node(webDomain = BANK)))
        )

        assertTrue(parser.webDomains.all { it.dalVerified })
    }

    @Test
    fun domainsDifferingOnlyByCaseAreOneDomain() {
        val f = Fixture()
        val parser = f.parser(
            "com.android.chrome",
            Node(children = listOf(Node(webDomain = "Bank.Example"), Node(webDomain = "bank.example")))
        )

        assertEquals(1, parser.webDomains.size)
    }

    // --- focused domain ---

    @Test
    fun domainOfFocusedFieldIsTheOnlyFocusedDomain() {
        val f = Fixture()
        val parser = f.parser(
            "com.android.chrome",
            Node(children = listOf(
                Node(webDomain = EVIL, children = listOf(Node(field = "password"))),
                Node(webDomain = BANK, children = listOf(Node(field = "password", focused = true))),
            ))
        )

        assertEquals(listOf(BANK), parser.webDomains.filter { it.focused }.map { it.domain })
        assertEquals(BANK, parser.primaryHost)
    }

    @Test
    fun soleDomainIsFocusedWhenNothingHasFocus() {
        val f = Fixture()
        val parser = f.bankClaims("com.android.chrome", listOf("com.android.chrome"))

        assertTrue(parser.domain(BANK).focused)
    }

    @Test
    fun noDomainIsFocusedWhenItCannotBeDetermined() {
        val f = Fixture()
        val parser = f.parser(
            "com.android.chrome",
            Node(children = listOf(Node(webDomain = EVIL), Node(webDomain = BANK)))
        )

        assertTrue(parser.webDomains.none { it.focused })
        assertNull(parser.primaryHost)
    }

    @Test
    fun passwordFieldDomainIsFocusedWhenNothingHasFocus() {
        val f = Fixture()
        val parser = f.parser(
            "com.android.chrome",
            Node(children = listOf(
                Node(webDomain = EVIL),
                Node(webDomain = BANK, children = listOf(Node(field = "password"))),
            ))
        )

        assertEquals(listOf(BANK), parser.webDomains.filter { it.focused }.map { it.domain })
    }

    @Test
    fun focusedDomainFlagDoesNotImplyVerified() {
        val f = Fixture()
        val parser = f.parser(
            "com.evil",
            Node(webDomain = BANK, children = listOf(Node(field = "password", focused = true)))
        )

        assertTrue(parser.domain(BANK).focused)
        assertFalse(parser.domain(BANK).dalVerified)
    }

    @Test
    fun webDomainFlagsSurviveMetadataJson() {
        val metadata = AutofillMetadata(
            webDomains = setOf(WebDomain("https", BANK, dalVerified = true, focused = true), WebDomain(null, EVIL)),
            saveInfo = null
        )
        val roundTripped = AutofillMetadata.fromJsonString(metadata.toJsonString())

        assertEquals(metadata.webDomains, roundTripped.webDomains)
        assertTrue(roundTripped.webDomains.single { it.domain == BANK }.focused)
        assertFalse(roundTripped.webDomains.single { it.domain == EVIL }.dalVerified)
    }

    // --- M-2: only visible fields of the focused domain are filled ---

    @Test
    fun fieldsOfOtherDomainsAreNotFilled() {
        val f = Fixture()
        val bankPassword = Node(field = "password", focused = true)
        val bankUser = Node(field = "username")
        val evilPassword = Node(field = "password")
        val parser = f.parser(
            "com.android.chrome",
            Node(children = listOf(
                Node(webDomain = BANK, children = listOf(bankUser, bankPassword)),
                Node(webDomain = EVIL, children = listOf(evilPassword)),
            ))
        )

        assertEquals(setOf(bankUser.autofillId, bankPassword.autofillId), parser.fillableIds())
    }

    @Test
    fun nativeFieldsAreNotFilledWhenAWebFieldHasFocus() {
        val f = Fixture()
        val nativePassword = Node(field = "password")
        val webPassword = Node(field = "password", focused = true)
        val parser = f.parser(
            "com.android.chrome",
            Node(children = listOf(nativePassword, Node(webDomain = BANK, children = listOf(webPassword))))
        )

        assertEquals(setOf(webPassword.autofillId), parser.fillableIds())
    }

    @Test
    fun webFieldsAreNotFilledWhenANativeFieldHasFocus() {
        val f = Fixture()
        val nativePassword = Node(field = "password", focused = true)
        val webPassword = Node(field = "password")
        val parser = f.parser(
            "com.evil",
            Node(children = listOf(nativePassword, Node(webDomain = BANK, children = listOf(webPassword))))
        )

        assertEquals(setOf(nativePassword.autofillId), parser.fillableIds())
    }

    @Test
    fun aFieldDeclaringItsOwnDomainDoesNotInheritTheParentsDomain() {
        val f = Fixture()
        val bankPassword = Node(field = "password", focused = true)
        val iframePassword = Node(field = "password", webDomain = EVIL)
        val parser = f.parser(
            "com.android.chrome",
            Node(webDomain = BANK, children = listOf(bankPassword, iframePassword))
        )

        assertEquals(setOf(bankPassword.autofillId), parser.fillableIds())
    }

    @Test
    fun everyVisibleFieldIsFilledWhenThereIsNoWebOrigin() {
        val f = Fixture()
        val user = Node(field = "username")
        val password = Node(field = "password", focused = true)
        val parser = f.parser("com.example.app", Node(children = listOf(user, password)))

        assertFalse(parser.isWebOrigin)
        assertEquals(setOf(user.autofillId, password.autofillId), parser.fillableIds())
    }

    @Test
    fun hiddenFieldsAreNotFilled() {
        val f = Fixture()
        val visible = Node(field = "password", focused = true)
        val invisible = Node(field = "password", visibility = View.INVISIBLE)
        val gone = Node(field = "password", visibility = View.GONE)
        val tinyWidth = Node(field = "password", width = 1)
        val tinyHeight = Node(field = "password", height = 1)
        val zeroSize = Node(field = "password", width = 0, height = 0)
        val inHiddenParent = Node(field = "password")
        val parser = f.parser(
            "com.example.app",
            Node(children = listOf(
                visible, invisible, gone, tinyWidth, tinyHeight, zeroSize,
                Node(visibility = View.INVISIBLE, children = listOf(inHiddenParent)),
            ))
        )

        assertEquals(setOf(visible.autofillId), parser.fillableIds())
    }

    // --- appId source ---

    @Test
    fun appIdFromComponentIsTrusted() {
        val f = Fixture()
        val parser = f.bankClaims("com.evil", listOf("com.other"))

        assertEquals("com.evil", parser.appId)
        assertEquals(AppIdSource.COMPONENT, parser.appIdSource)
        assertTrue(parser.appIdTrusted)
    }

    @Test
    fun appIdFromViewNodePackageIsNotTrusted() {
        val f = Fixture()
        val parser = f.bankClaims(null, listOf("com.bank.app"))

        assertEquals("com.bank.app", parser.appId)
        assertEquals(AppIdSource.VIEW_NODE, parser.appIdSource)
        assertFalse(parser.appIdTrusted)
    }

    @Test
    fun appIdFromExcludedComponentFallsBackToUntrustedViewNodePackage() {
        val f = Fixture()
        val parser = f.bankClaims("android", listOf("com.bank.app"))

        assertEquals("com.bank.app", parser.appId)
        assertFalse(parser.appIdTrusted)
    }

    @Test
    fun noPackagesMeansNoAppIdSource() {
        val f = Fixture()
        val parser = f.parser(null, Node())

        assertEquals(null, parser.appId)
        assertEquals(AppIdSource.NONE, parser.appIdSource)
        assertFalse(parser.appIdTrusted)
    }

    @Test
    fun appIdSourceUsesStableNamesInMetadataJson() {
        for ((source, name) in mapOf(
            AppIdSource.COMPONENT to "component",
            AppIdSource.VIEW_NODE to "viewNode",
            AppIdSource.WINDOW_TITLE to "windowTitle",
            AppIdSource.NONE to "none",
        )) {
            val json = AutofillMetadata(appIdSource = source, webDomains = emptySet(), saveInfo = null).toJsonString()
            assertTrue(json, json.contains("\"appIdSource\":\"$name\""))
            assertEquals(source, AutofillMetadata.fromJsonString(json).appIdSource)
        }
    }
}
