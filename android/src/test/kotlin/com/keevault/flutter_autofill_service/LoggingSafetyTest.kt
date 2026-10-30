package com.keevault.flutter_autofill_service

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

/**
 * Saved credentials travel inside intent extras (AutofillMetadata.saveInfo) and datasets
 * (PwDataset) and debug logs end up in logcat, bug reports and shared log files. Nothing that
 * can contain a credential value may be written to a log.
 */
class LoggingSafetyTest {

    private val secretUser = "alice@example.test"
    private val secretPassword = "hunter2-correct-horse"

    // --- the rule: no log statement may reference credential-bearing values ---

    private data class Rule(val pattern: Regex, val why: String)

    private val rules = listOf(
        Rule(Regex("""\bextras\b|\bget\w*Extra\b"""), "intent extras hold AutofillMetadata, including the saved credentials; log IntentHelpers.describeForLog(intent)"),
        Rule(Regex("""\b(?:password|username)\b"""), "credential values must not be logged"),
        Rule(Regex("""\bsaveInfo\b|\bSaveInfoMetadata\b|\bAutofillMetadata\b|\bPwDataset\b"""), "these types hold credentials"),
        Rule(Regex("""\bpwDatasets?\b(?!\.size\b)"""), "datasets hold credentials"),
        Rule(Regex("""\btoJson(?:String)?\b|\bEXTRA_NAME\b"""), "serialised metadata contains the saved credentials"),
        Rule(Regex("""\bmetadata\b"""), "log AutofillMetadata only through hideSensitiveMetadata()"),
    )

    /** Returns the reason each log statement in [source] breaks a rule, keyed by its line number. */
    private fun violations(source: String): List<Pair<Int, String>> =
        logStatements(source).flatMap { (line, code) ->
            // hideSensitiveMetadata() is the one approved way to describe metadata
            val checked = code.replace(Regex("""hideSensitiveMetadata\([^)]*\)"""), " ")
            rules.filter { it.pattern.containsMatchIn(checked) }.map { line to it.why }
        }

    @Test
    fun noLogStatementReferencesCredentialBearingValues() {
        val sourceDir = File("src/main/kotlin")
        assertTrue("run from the android module directory: ${File(".").absolutePath}", sourceDir.isDirectory)
        val files = sourceDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty())

        val problems = files.flatMap { file ->
            violations(file.readText()).map { (line, why) -> "${file.name}:$line - $why" }
        }

        assertTrue("Log statements must not include credential values:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun theRuleFlagsTheLoggingThatLeakedBefore() {
        val leakyOnNewIntent = """
            logger.info {
                "We got a new intent. ${'$'}intent (extras: ${'$'}{
                    intent.extras?.keySet()?.map { it to intent.extras?.get(it) }
                })"
            }
        """
        assertFalse(violations(leakyOnNewIntent).isEmpty())
    }

    @Test
    fun theRuleFlagsOtherWaysOfLoggingCredentials() {
        val leaks = listOf(
            """logger.debug { "dataset ${'$'}{pw.password}" }""",
            """logger.debug { "user ${'$'}{saved.username}" }""",
            """logger.debug { "got ${'$'}metadata" }""",
            """logger.debug { "got ${'$'}{metadata.toJsonString()}" }""",
            """logger.warn { "dataset ${'$'}pwDataset" }""",
            """logger.warn { "datasets ${'$'}{pwDatasets.first()}" }""",
            """logger.error({ "bad ${'$'}{intent.getStringExtra("AutofillMetadata")}" })""",
            """logger.trace { "if ${'$'}{ if (x) "a" else password }" }""",
        )
        for (leak in leaks) {
            assertFalse("should be flagged: $leak", violations(leak).isEmpty())
        }
    }

    @Test
    fun theRuleAllowsSafeLogging() {
        val safe = listOf(
            """logger.debug { "Got metadata: ${'$'}{hideSensitiveMetadata(metadata)}" }""",
            """logger.info { "We got a new intent. ${'$'}{IntentHelpers.describeForLog(intent)}" }""",
            """logger.warn { "got resultWithDatasets with ${'$'}{pwDatasets.size} entries." }""",
            """logger.warn { "No known password." }""",
            """logger.warn { "username autofill value was not text." }""",
            """logger.debug { "usernameId=${'$'}usernameId passwordId=${'$'}passwordId" }""",
            """logger.error({ "Too many ids ${'$'}{autoFillIds.size} for ${'$'}{parser.appId}" })""",
        )
        for (statement in safe) {
            assertTrue("should be allowed: $statement -> ${violations(statement)}", violations(statement).isEmpty())
        }
    }

    // --- defence in depth: accidental toString() must not reveal credentials ---

    @Test
    fun toStringOfCredentialHoldersIsRedacted() {
        val saveInfo = SaveInfoMetadata(secretUser, secretPassword, true)
        val metadata = AutofillMetadata(webDomains = emptySet(), saveInfo = saveInfo)
        val dataset = PwDataset("id", "label", secretUser, secretPassword)

        for (text in listOf(saveInfo.toString(), metadata.toString(), dataset.toString(), "$metadata", "${listOf(dataset)}")) {
            assertFalse(text, text.contains(secretUser))
            assertFalse(text, text.contains(secretPassword))
        }
    }

    @Test
    fun redactionDoesNotAffectSerialisation() {
        val metadata = AutofillMetadata(webDomains = emptySet(), saveInfo = SaveInfoMetadata(secretUser, secretPassword, null))
        val roundTripped = AutofillMetadata.fromJsonString(metadata.toJsonString())

        assertEquals(secretUser, roundTripped.saveInfo?.username)
        assertEquals(secretPassword, roundTripped.saveInfo?.password)
    }

    // --- IntentHelpers.describeForLog ---

    @Test
    fun intentDescriptionContainsExtraNamesButNoValues() {
        val metadataJson = AutofillMetadata(
            webDomains = emptySet(),
            saveInfo = SaveInfoMetadata(secretUser, secretPassword, null)
        ).toJsonString()
        val extras = mock(Bundle::class.java)
        `when`(extras.keySet()).thenReturn(setOf("autofill_mode", AutofillMetadata.EXTRA_NAME))
        `when`(extras.getString(AutofillMetadata.EXTRA_NAME)).thenReturn(metadataJson)
        `when`(extras.get(AutofillMetadata.EXTRA_NAME)).thenReturn(metadataJson)
        `when`(extras.toString()).thenReturn("Bundle[{AutofillMetadata=$metadataJson}]")
        val component = mock(ComponentName::class.java)
        `when`(component.flattenToShortString()).thenReturn("com.example/.AutofillActivity")
        val intent = mock(Intent::class.java)
        `when`(intent.extras).thenReturn(extras)
        `when`(intent.action).thenReturn("android.intent.action.MAIN")
        `when`(intent.component).thenReturn(component)
        `when`(intent.getStringExtra(AutofillMetadata.EXTRA_NAME)).thenReturn(metadataJson)

        val description = IntentHelpers.describeForLog(intent)

        assertEquals(
            "Intent(action=android.intent.action.MAIN, component=com.example/.AutofillActivity, extraKeys=[AutofillMetadata, autofill_mode])",
            description
        )
        assertFalse(description.contains(secretUser))
        assertFalse(description.contains(secretPassword))
    }

    @Test
    fun intentDescriptionHandlesMissingIntentAndExtras() {
        assertEquals("null", IntentHelpers.describeForLog(null))
        val intent = mock(Intent::class.java)
        assertEquals("Intent(action=null, component=null, extraKeys=[])", IntentHelpers.describeForLog(intent))
    }
}

/**
 * The code of each `logger.<level>(...)` / `logger.<level> { ... }` call in [source], with the
 * literal text of string literals removed, so that only identifiers and expressions remain. The
 * Int is the 1-based line the call starts on.
 */
private fun logStatements(source: String): List<Pair<Int, String>> {
    val call = Regex("""\blogger\.(?:trace|debug|info|warn|error)\s*[({]""")
    return call.findAll(source).map { match ->
        val open = match.range.last
        val line = source.substring(0, match.range.first).count { it == '\n' } + 1
        line to ScannedCode(source).extractBlock(open)
    }.toList()
}

private class ScannedCode(private val src: String) {
    private val code = StringBuilder()

    fun extractBlock(openIndex: Int): String {
        var depth = 0
        var i = openIndex
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' -> { i = consumeString(i); continue }
                c == '(' || c == '{' || c == '[' -> { depth++; code.append(c) }
                c == ')' || c == '}' || c == ']' -> {
                    depth--
                    code.append(c)
                    if (depth == 0) return code.toString()
                }
                else -> code.append(c)
            }
            i++
        }
        return code.toString()
    }

    /** Skips the literal text of the string starting at [start], keeping its `$` expressions. */
    private fun consumeString(start: Int): Int {
        val raw = src.startsWith("\"\"\"", start)
        var i = start + if (raw) 3 else 1
        while (i < src.length) {
            val c = src[i]
            when {
                raw && src.startsWith("\"\"\"", i) -> return i + 3
                !raw && c == '"' -> return i + 1
                !raw && c == '\\' -> i += 2
                c == '$' && i + 1 < src.length && src[i + 1] == '{' -> i = consumeTemplateExpression(i + 1)
                c == '$' && i + 1 < src.length && (src[i + 1].isLetter() || src[i + 1] == '_') -> {
                    var j = i + 1
                    while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                    code.append(' ').append(src, i + 1, j).append(' ')
                    i = j
                }
                else -> i++
            }
        }
        return i
    }

    /** [openBrace] is the index of the `{` of a `${...}`. Returns the index after its `}`. */
    private fun consumeTemplateExpression(openBrace: Int): Int {
        var depth = 0
        var i = openBrace
        code.append(' ')
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' -> { i = consumeString(i); continue }
                c == '{' -> { depth++; i++ }
                c == '}' -> {
                    depth--
                    i++
                    if (depth == 0) { code.append(' '); return i }
                }
                else -> { code.append(c); i++ }
            }
        }
        return i
    }
}
