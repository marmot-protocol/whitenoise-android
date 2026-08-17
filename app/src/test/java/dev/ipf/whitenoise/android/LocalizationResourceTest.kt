package dev.ipf.whitenoise.android

import dev.ipf.whitenoise.android.core.WhiteNoiseUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourceTest {
    @Test
    fun defaultUserVisibleStringsUseSignInAndSignOutTerminology() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .first { it.exists() }
        val forbiddenTerminology = Regex("\\b(?:login|log\\s+in|logout|log\\s+out)\\b", RegexOption.IGNORE_CASE)
        val strings = parseStringsResource(File(resDir, "values"))

        val offendingKeys =
            strings.strings
                .filterValues { forbiddenTerminology.containsMatchIn(it) }
                .keys

        assertTrue("User-visible strings use login/logout terminology: $offendingKeys", offendingKeys.isEmpty())
    }

    @Test
    fun localizedStringFilesHaveTheSameKeysAsDefaultEnglish() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .first { it.exists() }
        val defaultKeys = resourceNames(File(resDir, "values"))

        resDir
            .listFiles()
            .orEmpty()
            .filter {
                it.isDirectory &&
                    it.name.startsWith("values-") &&
                    File(it, "strings.xml").exists()
            }.forEach { localized ->
                assertEquals(localized.path, defaultKeys, resourceNames(localized))
            }
    }

    @Test
    fun localizedStringFilesDoNotCopyEnglishUserVisibleText() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .first { it.exists() }
        val englishDirectory = File(resDir, "values")

        resDir
            .listFiles()
            .orEmpty()
            .filter {
                it.isDirectory &&
                    it.name.startsWith("values-") &&
                    File(it, "strings.xml").exists()
            }.forEach { localized ->
                val copiedKeys =
                    copiedEnglishResourceKeys(
                        english = parseStringsResource(englishDirectory),
                        localized = parseStringsResource(localized),
                        localeDirName = localized.name,
                    )

                assertTrue("${localized.path} copies English for $copiedKeys", copiedKeys.isEmpty())
            }
    }

    @Test
    fun missingPluralResourceNameIsDetectedByParityCheck() {
        val resDir =
            writeFixtureResDir(
                defaultStrings =
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <string name="hello">Hello</string>
                        <plurals name="items_count">
                            <item quantity="one">%d item</item>
                            <item quantity="other">%d items</item>
                        </plurals>
                    </resources>
                    """.trimIndent(),
                localizedStrings =
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <string name="hello">Bonjour</string>
                    </resources>
                    """.trimIndent(),
            )

        val defaultKeys = resourceNames(File(resDir, "values/strings.xml"))
        val localizedKeys = resourceNames(File(resDir, "values-fr/strings.xml"))

        assertTrue(defaultKeys.plurals.contains("items_count"))
        assertNotEquals(defaultKeys, localizedKeys)
    }

    @Test
    fun mismatchedStringAndPluralResourceTypesAreDetectedByParityCheck() {
        val resDir =
            writeFixtureResDir(
                defaultStrings =
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <plurals name="items_count">
                            <item quantity="one">%d item</item>
                            <item quantity="other">%d items</item>
                        </plurals>
                    </resources>
                    """.trimIndent(),
                localizedStrings =
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <string name="items_count">%d éléments</string>
                    </resources>
                    """.trimIndent(),
            )

        val defaultKeys = resourceNames(File(resDir, "values/strings.xml"))
        val localizedKeys = resourceNames(File(resDir, "values-fr/strings.xml"))

        assertNotEquals(defaultKeys, localizedKeys)
    }

    @Test
    fun nonTranslatablePluralIsExcludedFromParityCheck() {
        val resDir =
            writeFixtureResDir(
                defaultStrings =
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <string name="hello">Hello</string>
                        <plurals name="internal_count" translatable="false">
                            <item quantity="one">%d item</item>
                            <item quantity="other">%d items</item>
                        </plurals>
                    </resources>
                    """.trimIndent(),
                localizedStrings =
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <string name="hello">Bonjour</string>
                    </resources>
                    """.trimIndent(),
            )

        val defaultKeys = resourceNames(File(resDir, "values/strings.xml"))
        val localizedKeys = resourceNames(File(resDir, "values-fr/strings.xml"))

        assertEquals(defaultKeys, localizedKeys)
    }

    @Test
    fun copiedEnglishPluralItemTextIsDetected() {
        val resDir =
            writeFixtureResDir(
                defaultStrings =
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <plurals name="items_count">
                            <item quantity="one">%d item</item>
                            <item quantity="other">%d items</item>
                        </plurals>
                    </resources>
                    """.trimIndent(),
                localizedStrings =
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <plurals name="items_count">
                            <item quantity="few">%d items</item>
                            <item quantity="other">%d éléments</item>
                        </plurals>
                    </resources>
                    """.trimIndent(),
            )

        val copiedKeys =
            copiedEnglishResourceKeys(
                englishFile = File(resDir, "values/strings.xml"),
                localizedFile = File(resDir, "values-fr/strings.xml"),
                localeDirName = "values-fr",
            )

        assertEquals(setOf("items_count[few]"), copiedKeys)
    }

    @Test
    fun differingPluralQuantitySetsAreAccepted() {
        val resDir =
            writeFixtureResDir(
                defaultStrings =
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <plurals name="items_count">
                            <item quantity="one">%d item</item>
                            <item quantity="other">%d items</item>
                        </plurals>
                    </resources>
                    """.trimIndent(),
                localizedStrings =
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <plurals name="items_count">
                            <item quantity="one">%d элемент</item>
                            <item quantity="few">%d элемента</item>
                            <item quantity="many">%d элементов</item>
                            <item quantity="other">%d элементов</item>
                        </plurals>
                    </resources>
                    """.trimIndent(),
                localizedDirName = "values-ru",
            )

        val defaultKeys = resourceNames(File(resDir, "values/strings.xml"))
        val localizedKeys = resourceNames(File(resDir, "values-ru/strings.xml"))

        assertEquals(defaultKeys, localizedKeys)
        assertTrue(
            copiedEnglishResourceKeys(
                englishFile = File(resDir, "values/strings.xml"),
                localizedFile = File(resDir, "values-ru/strings.xml"),
                localeDirName = "values-ru",
            ).isEmpty(),
        )
    }

    @Test
    fun agentConnectorCopyIsExplanationFirstAndEvergreenAcrossAllLocales() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .first { it.exists() }

        val resourceFiles =
            buildList {
                add(File(resDir, "values/strings.xml"))
                resDir
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory && it.name.startsWith("values-") }
                    .map { File(it, "strings.xml") }
                    .filter { it.exists() }
                    .forEach { add(it) }
            }

        val offenders = resourceFiles.flatMap(::agentConnectorCopyOffenders)

        assertEquals(AGENT_CONNECTOR_DOCS_URL, WhiteNoiseUrls.AGENT_CONNECTOR_DOCS)
        assertTrue(
            "Agent connector copy must explain the installation flow first. Prompts must link " +
                "the evergreen MDK connector guide once, include one %1\$s placeholder, explain " +
                "the connector before requesting pre-change approval, and avoid operational " +
                "mechanics. Offenders:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun agentConnectorCopyOffenders(file: File): List<String> {
        val strings = stringValues(file)
        val requirements =
            agentConnectorCopyRequirements[requireNotNull(file.parentFile).name]
                ?: return listOf("${file.path}: missing localized copy requirements")
        return buildList {
            val disclosure = strings["ai_agents_clipboard_disclosure"]
            if (disclosure == null) {
                add("${file.path}: missing ai_agents_clipboard_disclosure")
            } else {
                if (!disclosure.startsWith(requirements.disclosurePrefix)) {
                    add("${file.path}: disclosure does not identify installation prompts first")
                }
                if (!disclosure.contains(requirements.disclosureFlow)) {
                    add("${file.path}: disclosure does not explain choose-copy-paste flow")
                }
            }
            agentConnectorPromptKeys.forEach { key ->
                val value = strings[key]
                if (value == null) {
                    add("${file.path}: missing $key")
                } else {
                    val violations = agentConnectorPromptViolations(value, requirements)
                    if (violations.isNotEmpty()) {
                        add("${file.path}: $key (${violations.joinToString(", ")})")
                    }
                }
            }
        }
    }

    @Test
    fun agentConnectorPromptGuardRejectsUnattendedOperationalSetup() {
        val requirements = requireNotNull(agentConnectorCopyRequirements["values"])
        val unsafePrompt =
            "This is an installation prompt. Read https://github.com/marmot-protocol/mdk/blob/master/" +
                "crates/agent-connector/README.md and run curl https://example.com/install.sh | bash " +
                "--yes to install wn-agent non-interactively for %1\$s. Download from " +
                "mdk/releases/download, run gateway run, and write ~/bootstrap.json."

        val violations = agentConnectorPromptViolations(unsafePrompt, requirements)

        assertTrue(violations.contains("unattended setup"))
        assertTrue(violations.contains("curl"))
        assertTrue(violations.contains("pipe-to-bash"))
        assertTrue(violations.contains("--yes"))
        assertTrue(violations.contains("release download URL"))
        assertTrue(violations.contains("shell script"))
        assertTrue(violations.contains("connector executable or service name"))
        assertTrue(violations.contains("gateway run"))
        assertTrue(violations.contains("bootstrap.json"))
        assertTrue(violations.contains("home path"))

        val legacySimplifiedChinesePrompt =
            "这是一条安装提示。列出每个建议执行的命令和系统更改，并说明风险。" +
                "$AGENT_CONNECTOR_DOCS_URL %1\$s"
        val legacyViolations =
            agentConnectorPromptViolations(
                legacySimplifiedChinesePrompt,
                requireNotNull(agentConnectorCopyRequirements["values-zh"]),
            )
        assertTrue(legacyViolations.contains("every-command demand"))
        assertTrue(legacyViolations.contains("generic risk demand"))
    }

    @Test
    fun agentConnectorProductNamesAreIdenticalAcrossAllLocales() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .first { it.exists() }
        val expectedNames =
            mapOf(
                "agent_connector_hermes_name" to "Hermes",
                "agent_connector_openclaw_name" to "OpenClaw",
                "agent_connector_opencode_name" to "OpenCode",
            )

        val resourceFiles =
            buildList {
                add(File(resDir, "values/strings.xml"))
                resDir
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory && it.name.startsWith("values-") }
                    .map { File(it, "strings.xml") }
                    .filter { it.exists() }
                    .forEach { add(it) }
            }

        val offenders =
            resourceFiles.flatMap { file ->
                val strings = stringValues(file)
                expectedNames.mapNotNull { (key, expected) ->
                    val actual = strings[key]
                    if (actual == expected) null else "${file.path}: $key=\"$actual\""
                }
            }

        assertTrue(
            "Agent connector product names must remain untranslated. Offenders:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun relayListLabelsDescribeUserVisibleBehavior() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .first { it.exists() }
        val englishValues = stringValues(File(resDir, "values/strings.xml"))

        assertEquals("Where I post", englishValues["nip_65"])
        assertEquals("Where I receive", englishValues["inbox"])
    }

    // Guards the umbrella sweep from #381: user-visible string values must not
    // expose raw NIP specification identifiers (e.g. "NIP-05", "NIP-65",
    // "NIP-44") or the deprecated "NIP-EE" naming. NIP numbers are protocol
    // implementation detail and mean nothing to a non-developer user; group
    // encryption is the "Marmot Protocol", not "NIP-EE". Code identifiers,
    // log lines and code comments are out of scope — this inspects the
    // textContent of every user-visible resource (<string> plus the <item>
    // children of <plurals> and <string-array>), not their keys (so the
    // `nip_05` / `nip_65` resource *keys* are unaffected). If you need to
    // reference a NIP for power users, keep it in a developer-facing log or
    // comment, not in a user-visible string.
    // Historical localization issue: no default locale should carry incomplete strings.
    @Test
    fun userVisibleStringsDoNotExposeRawNipIdentifiers() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .first { it.exists() }

        val resourceFiles =
            buildList {
                add(File(resDir, "values/strings.xml"))
                resDir
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory && it.name.startsWith("values-") }
                    .map { File(it, "strings.xml") }
                    .filter { it.exists() }
                    .forEach { add(it) }
            }

        val offenders =
            resourceFiles.flatMap { file ->
                userVisibleValues(file)
                    .filter { (_, value) -> forbiddenNipPattern.containsMatchIn(value) }
                    .map { (key, value) -> "${file.path}: $key=\"$value\"" }
            }

        assertTrue(
            "User-visible string values must not expose raw NIP identifiers " +
                "(NIP-<number> or the deprecated NIP-EE). Replace them with a " +
                "human-readable description (see #381). Offenders:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun resourceNames(file: File): ResourceNames {
        val content = parseStringsResource(file)
        return ResourceNames(strings = content.strings.keys, plurals = content.plurals.keys)
    }

    private fun stringValues(file: File): Map<String, String> = parseStringsFile(file).strings

    private fun copiedEnglishResourceKeys(
        englishFile: File,
        localizedFile: File,
        localeDirName: String,
    ): Set<String> =
        copiedEnglishResourceKeys(
            english = parseStringsResource(englishFile),
            localized = parseStringsResource(localizedFile),
            localeDirName = localeDirName,
        )

    private fun copiedEnglishResourceKeys(
        english: StringsFileContent,
        localized: StringsFileContent,
        localeDirName: String,
    ): Set<String> {
        val localeExemptions = localeScopedAllowedKeys[localeDirName].orEmpty()

        val copiedStringKeys =
            localized.strings
                .filter { (key, value) ->
                    key !in identicalValueAllowedKeys &&
                        key !in localeExemptions &&
                        value.isNotBlank() &&
                        value == english.strings[key]
                }.keys

        val copiedPluralKeys =
            localized.plurals.flatMap { (name, localizedQuantities) ->
                if (name in identicalValueAllowedKeys || name in localeExemptions) {
                    emptyList()
                } else {
                    val englishValues = english.plurals[name].orEmpty().values
                    localizedQuantities
                        .filter { (_, localizedValue) ->
                            localizedValue.isNotBlank() &&
                                localizedValue in englishValues
                        }.map { (quantity, _) -> "$name[$quantity]" }
                }
            }

        return copiedStringKeys + copiedPluralKeys.toSet()
    }

    private data class StringsFileContent(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
    )

    private data class ResourceNames(
        val strings: Set<String>,
        val plurals: Set<String>,
    )

    private fun parseStringsFile(file: File): StringsFileContent {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
        val strings =
            buildMap {
                val nodes = document.getElementsByTagName("string")
                for (index in 0 until nodes.length) {
                    val item = nodes.item(index)
                    // translatable="false" strings (format-only or fixed keywords)
                    // live in the default values only and must not be replicated to
                    // locale files, so they are not part of the parity contract.
                    if (item.attributes.getNamedItem("translatable")?.nodeValue == "false") continue
                    put(item.attributes.getNamedItem("name").nodeValue, item.textContent)
                }
            }

        val plurals =
            buildMap {
                val nodes = document.getElementsByTagName("plurals")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index)
                    if (node.attributes.getNamedItem("translatable")?.nodeValue == "false") continue
                    val name = node.attributes.getNamedItem("name").nodeValue
                    val quantities =
                        buildMap {
                            val items = node.childNodes
                            for (i in 0 until items.length) {
                                val child = items.item(i)
                                if (child.nodeName == "item") {
                                    val quantity =
                                        child.attributes?.getNamedItem("quantity")?.nodeValue
                                            ?: "unknown"
                                    put(quantity, child.textContent)
                                }
                            }
                        }
                    put(name, quantities)
                }
            }

        return StringsFileContent(strings = strings, plurals = plurals)
    }

    private fun parseStringsResource(fileOrDirectory: File): StringsFileContent {
        if (fileOrDirectory.isFile) return parseStringsFile(fileOrDirectory)
        val contents =
            fileOrDirectory
                .listFiles { file -> file.isFile && file.extension == "xml" }
                .orEmpty()
                .sortedBy { it.name }
                .map(::parseStringsFile)
        return StringsFileContent(
            strings = contents.flatMap { it.strings.entries }.associate { it.toPair() },
            plurals = contents.flatMap { it.plurals.entries }.associate { it.toPair() },
        )
    }

    private fun writeFixtureResDir(
        defaultStrings: String,
        localizedStrings: String,
        localizedDirName: String = "values-fr",
    ): File {
        val resDir = Files.createTempDirectory("localization-resource-test").toFile()
        val valuesDir = File(resDir, "values").also { it.mkdirs() }
        val localizedDir = File(resDir, localizedDirName).also { it.mkdirs() }
        File(valuesDir, "strings.xml").writeText(defaultStrings)
        File(localizedDir, "strings.xml").writeText(localizedStrings)
        return resDir
    }

    // Extracts every *user-visible* localized resource value: plain <string>
    // entries plus the <item> children of <plurals> and <string-array>. Unlike
    // parseStringsFile (used for the key-parity contract, which keys on
    // <string> and <plurals> names but not per-quantity categories), this
    // powers the NIP guard so a raw NIP token
    // cannot slip in through a quantity string or array item. translatable=
    // "false" strings are included here because they are still rendered to the
    // user; only their absence from locale files is exempt from parity, not
    // their content from the NIP audit. Keys are made unique per source:
    // "<name>" for strings, "<name>[<quantity>]" for plurals items, and
    // "<name>[<index>]" for string-array items.
    private fun userVisibleValues(file: File): Map<String, String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
        return buildMap {
            val strings = document.getElementsByTagName("string")
            for (index in 0 until strings.length) {
                val item = strings.item(index)
                put(item.attributes.getNamedItem("name").nodeValue, item.textContent)
            }

            val plurals = document.getElementsByTagName("plurals")
            for (index in 0 until plurals.length) {
                val node = plurals.item(index)
                val name = node.attributes.getNamedItem("name").nodeValue
                val items = node.childNodes
                for (i in 0 until items.length) {
                    val child = items.item(i)
                    if (child.nodeName == "item") {
                        val quantity =
                            child.attributes?.getNamedItem("quantity")?.nodeValue ?: "unknown"
                        put("$name[$quantity]", child.textContent)
                    }
                }
            }

            val arrays = document.getElementsByTagName("string-array")
            for (index in 0 until arrays.length) {
                val node = arrays.item(index)
                val name = node.attributes.getNamedItem("name").nodeValue
                val items = node.childNodes
                var seen = 0
                for (i in 0 until items.length) {
                    val child = items.item(i)
                    if (child.nodeName == "item") {
                        put("$name[$seen]", child.textContent)
                        seen++
                    }
                }
            }
        }
    }

    private fun agentConnectorPromptViolations(
        prompt: String,
        requirements: AgentConnectorCopyRequirements,
    ): List<String> {
        val violations = mutableListOf<String>()
        if (prompt.windowed(AGENT_CONNECTOR_DOCS_URL.length).count { it == AGENT_CONNECTOR_DOCS_URL } != 1) {
            violations += "missing evergreen docs URL"
        }
        if (prompt.windowed(AGENT_CONNECTOR_NPUB_PLACEHOLDER.length).count {
                it == AGENT_CONNECTOR_NPUB_PLACEHOLDER
            } != 1
        ) {
            violations += "missing single %1\$s placeholder"
        }
        if (!prompt.startsWith(requirements.promptPrefix)) {
            violations += "missing installation-prompt introduction"
        }
        val orderedSegments =
            listOf(
                "plain-language explanation" to requirements.explanation,
                "pre-change approval" to requirements.approval,
                "approved install and verification" to requirements.postApproval,
            )
        val segmentIndexes = orderedSegments.map { (_, segment) -> prompt.indexOf(segment) }
        orderedSegments.zip(segmentIndexes).forEach { (entry, index) ->
            if (index < 0) violations += "missing ${entry.first}"
        }
        if (segmentIndexes.all { it >= 0 } && segmentIndexes.zipWithNext().any { (first, second) -> first >= second }) {
            violations += "not explanation-first"
        }
        agentConnectorForbiddenPatterns.forEach { (label, pattern) ->
            if (pattern.containsMatchIn(prompt)) {
                violations += label
            }
        }
        return violations
    }

    private data class AgentConnectorCopyRequirements(
        val disclosurePrefix: String,
        val disclosureFlow: String,
        val promptPrefix: String,
        val explanation: String,
        val approval: String,
        val postApproval: String,
    )

    private companion object {
        const val AGENT_CONNECTOR_NPUB_PLACEHOLDER = "%1\$s"
        const val AGENT_CONNECTOR_DOCS_URL =
            "https://github.com/marmot-protocol/mdk/blob/master/crates/agent-connector/README.md"

        val agentConnectorPromptKeys =
            listOf(
                "agent_connector_hermes_prompt",
                "agent_connector_openclaw_prompt",
                "agent_connector_opencode_prompt",
            )

        val agentConnectorCopyRequirements =
            mapOf(
                "values" to
                    AgentConnectorCopyRequirements(
                        disclosurePrefix = "These are installation prompts.",
                        disclosureFlow = "Choose your agent, copy its prompt, and paste it into that agent",
                        promptPrefix = "This is an installation prompt",
                        explanation = "Explain to me how the connector works and what the installation will change.",
                        approval = "ask for my approval before making any changes.",
                        postApproval = "Once I approve, install and verify the connector",
                    ),
                "values-de" to
                    AgentConnectorCopyRequirements(
                        disclosurePrefix = "Dies sind Installationsanweisungen.",
                        disclosureFlow =
                            "Wähle deinen Agenten aus, kopiere seine Anweisung und füge sie " +
                                "in diesen Agenten ein",
                        promptPrefix = "Dies ist eine Installationsanweisung",
                        explanation =
                            "Erkläre mir, wie der Konnektor funktioniert und was die Installation " +
                                "ändern wird.",
                        approval = "bitte um meine Zustimmung, bevor du Änderungen vornimmst.",
                        postApproval = "Sobald ich zugestimmt habe, installiere und überprüfe den Konnektor",
                    ),
                "values-es" to
                    AgentConnectorCopyRequirements(
                        disclosurePrefix = "Estas son instrucciones de instalación.",
                        disclosureFlow = "Elige tu agente, copia su instrucción y pégala en ese agente",
                        promptPrefix = "Esta es una instrucción de instalación",
                        explanation = "Explícame cómo funciona el conector y qué cambiará la instalación.",
                        approval = "pide mi aprobación antes de realizar cualquier cambio.",
                        postApproval = "Una vez que lo apruebe, instala y verifica el conector",
                    ),
                "values-fr" to
                    AgentConnectorCopyRequirements(
                        disclosurePrefix = "Voici des invites d’installation.",
                        disclosureFlow = "Choisissez votre agent, copiez son invite et collez-la dans cet agent",
                        promptPrefix = "Ceci est une invite d’installation",
                        explanation =
                            "Expliquez-moi comment le connecteur fonctionne et ce que l’installation " +
                                "va modifier.",
                        approval = "demandez mon approbation avant d’apporter la moindre modification.",
                        postApproval = "Une fois mon approbation donnée, installez et vérifiez le connecteur",
                    ),
                "values-it" to
                    AgentConnectorCopyRequirements(
                        disclosurePrefix = "Questi sono prompt di installazione.",
                        disclosureFlow = "Scegli il tuo agente, copia il relativo prompt e incollalo in quell’agente",
                        promptPrefix = "Questo è un prompt di installazione",
                        explanation = "Spiegami come funziona il connettore e cosa cambierà l’installazione.",
                        approval = "chiedi la mia approvazione prima di apportare qualsiasi modifica.",
                        postApproval = "Dopo la mia approvazione, installa e verifica il connettore",
                    ),
                "values-pt" to
                    AgentConnectorCopyRequirements(
                        disclosurePrefix = "Estes são prompts de instalação.",
                        disclosureFlow = "Escolha seu agente, copie o prompt dele e cole-o nesse agente",
                        promptPrefix = "Este é um prompt de instalação",
                        explanation = "Explique como o conector funciona e o que a instalação alterará.",
                        approval = "peça minha aprovação antes de fazer qualquer alteração.",
                        postApproval = "Depois que eu aprovar, instale e verifique o conector",
                    ),
                "values-ru" to
                    AgentConnectorCopyRequirements(
                        disclosurePrefix = "Это запросы на установку.",
                        disclosureFlow = "Выберите своего агента, скопируйте запрос и вставьте его в этого агента",
                        promptPrefix = "Это запрос на установку",
                        explanation = "Объясните мне, как работает коннектор и что изменит установка.",
                        approval = "запросите мое одобрение, прежде чем вносить какие-либо изменения.",
                        postApproval = "После моего одобрения установите и проверьте коннектор",
                    ),
                "values-tr" to
                    AgentConnectorCopyRequirements(
                        disclosurePrefix = "Bunlar kurulum istemleridir.",
                        disclosureFlow =
                            "Ajanınızı seçin, istemini kopyalayın ve White Noise’a bağlamak için " +
                                "o ajana yapıştırın",
                        promptPrefix = "Bu bir kurulum istemidir",
                        explanation =
                            "Bağlayıcının nasıl çalıştığını ve kurulumun neleri değiştireceğini " +
                                "bana açıklayın.",
                        approval = "herhangi bir değişiklik yapmadan önce onayımı isteyin.",
                        postApproval = "Ben onayladıktan sonra bağlayıcıyı kurup doğrulayın",
                    ),
                "values-zh" to
                    AgentConnectorCopyRequirements(
                        disclosurePrefix = "这些是安装提示。",
                        disclosureFlow = "选择你的代理，复制相应提示并将其粘贴到该代理中",
                        promptPrefix = "这是一条安装提示",
                        explanation = "请向我解释连接器的工作方式以及安装会带来哪些更改。",
                        approval = "并在进行任何更改之前请求我的批准。",
                        postApproval = "我批准后，请安装并验证连接器",
                    ),
                "values-b+zh+Hant" to
                    AgentConnectorCopyRequirements(
                        disclosurePrefix = "這些是安裝提示。",
                        disclosureFlow = "選擇你的代理，複製相應提示並將其貼到該代理中",
                        promptPrefix = "這是一則安裝提示",
                        explanation = "請向我解釋連接器的運作方式以及安裝會帶來哪些變更。",
                        approval = "並在進行任何變更之前請求我的核准。",
                        postApproval = "我核准後，請安裝並驗證連接器",
                    ),
            )

        val agentConnectorForbiddenPatterns =
            listOf(
                "machine approval marker" to Regex("""APPROVAL_REQUIRED:"""),
                "every-command demand" to
                    Regex(
                        """show\s+every\s+(?:proposed\s+)?command|zeige\s+jeden\s+vorgeschlagenen\s+befehl|""" +
                            """muestra\s+cada\s+comando|montrez\s+chaque\s+commande|mostra\s+ogni\s+comando|""" +
                            """mostre\s+cada\s+comando|покажите\s+каждую\s+предлагаемую\s+команду|""" +
                            """önerilen\s+her\s+komutu|列出每(?:個建議執行|个建议执行)的命令""",
                        RegexOption.IGNORE_CASE,
                    ),
                "generic risk demand" to
                    Regex(
                        """explain\s+the\s+risks|erkläre\s+die\s+risiken|explica\s+los\s+riesgos|""" +
                            """expliquez\s+les\s+risques|spiega\s+i\s+rischi|explique\s+os\s+riscos|""" +
                            """объясните\s+риски|riskleri\s+açıklayın|說明風險|说明风险""",
                        RegexOption.IGNORE_CASE,
                    ),
                "unattended setup" to
                    Regex(
                        """non[- ]?interactiv|nicht\s+interaktiv|sin\s+interacci[oó]n|""" +
                            """sans\s+interaction|senza\s+interazione|sem\s+intera[cç][aã]o|""" +
                            """etkile[sş]imsiz|без\s+взаимодейств|非交[互動动]方式""",
                        RegexOption.IGNORE_CASE,
                    ),
                "curl" to Regex("""\bcurl\b""", RegexOption.IGNORE_CASE),
                "pipe-to-bash" to Regex("""\|\s*bash"""),
                "--yes" to Regex("""--yes\b"""),
                "--allow-welcomer" to Regex("""--allow-welcomer\b"""),
                "release download URL" to
                    Regex("""mdk/releases/download""", RegexOption.IGNORE_CASE),
                "shell script" to Regex("""\.sh\b"""),
                "connector executable or service name" to
                    Regex("""\bwn-(?:agent|opencode)(?:-[a-z0-9-]+)?\b""", RegexOption.IGNORE_CASE),
                "gateway run" to Regex("""gateway\s+run""", RegexOption.IGNORE_CASE),
                "bootstrap.json" to Regex("""bootstrap\.json"""),
                "home path" to Regex("""~\/"""),
            )

        // Matches raw NIP specification identifiers in user-visible copy:
        // "NIP-05", "NIP_44", "NIP 65", "NIP - 65", "NIP65", etc., plus the
        // deprecated "NIP-EE" naming. Case-insensitive. The leading `\b` plus
        // the requirement that "NIP" be followed by a separator/whitespace or
        // the number avoids false positives inside larger words such as
        // "SNIP-65" or "NIPSTER 65", while still tolerating the hyphen /
        // underscore / space (and spaced "NIP - 65") separators a translator
        // might introduce.
        val forbiddenNipPattern =
            Regex("""\bNIP(?:\s*[-_]\s*|\s+)?(?:\d+|EE)\b""", RegexOption.IGNORE_CASE)

        val identicalValueAllowedKeys =
            setOf(
                // Pure positional-format string ("current/total"); no
                // translatable text, identical across every locale by design.
                "conversation_search_match_count",
                // Aspect ratios and dimension formats are locale-independent;
                // Original/Standard are established photo-quality labels in
                // several supported languages.
                "photo_editor_crop_original",
                "photo_editor_crop_square",
                "photo_editor_crop_four_three",
                "photo_editor_crop_three_four",
                "photo_editor_crop_sixteen_nine",
                "photo_editor_crop_nine_sixteen",
                "photo_editor_quality_standard",
                "photo_editor_effective_quality",
                // Dialog confirm button; "OK" is shared verbatim across most locales.
                "ok",
                "admin",
                "app_name",
                // Standard file-format abbreviations are intentionally kept
                // identical in every locale.
                "attachment_type_android_package",
                "attachment_type_pdf",
                // In-app brand wordmark; the product name "White Noise" is kept
                // identical across every locale.
                "white_noise",
                "app_preferences",
                "actions",
                "bytes_count",
                // Brand/protocol names kept identical across every locale.
                "donate_method_bitcoin",
                "donate_method_lightning",
                "agent_connector_hermes_name",
                "agent_connector_openclaw_name",
                "agent_connector_opencode_name",
                "edit_history_original",
                "edit_history_version_label",
                "generic_message",
                "invitation",
                "key_packages",
                "language_chinese_simplified",
                "language_chinese_traditional",
                "language_english",
                "language_french",
                "language_german",
                "language_italian",
                "language_portuguese",
                "language_russian",
                "language_spanish",
                "language_turkish",
                "live",
                "message",
                "message_info_status",
                "mls",
                "nip_05",
                "no",
                "notification_channel_messages",
                // "Mentions" is the same word in French as in English.
                "notification_channel_mentions",
                // "Saturation" is the standard HSV term in both French and English.
                "color_picker_saturation",
                "notification_sender_in_group",
                "notifications",
                "online",
                "ref",
                "relay_health",
                "new_chat_optional_section",
                "relays",
                "settings_version_label",
                "status",
                "theme_amoled",
                "theme_system",
                "total",
                // Some translations may legitimately match English for
                // some keys/locales (e.g. "Video" in many languages, "Foto"
                // in DE/ES/IT/PT). Keep just the brand-shared tokens here
                // — everything else gets a real translation enforced by
                // localizedStringFilesDoNotCopyEnglishUserVisibleText.
                "reply_media_photo",
                "reply_media_video",
                "reply_media_voice",
                "reply_media_document",
                "toast_couldnt_process_video",
                // Brand / loan-word tokens in the auto-download matrix (#407)
                // that are legitimately identical to English in several
                // locales: "Wi-Fi"/"Roaming" as borrowed terms and the media
                // type names ("Audio", "Video", and "Images"/"Documents" in
                // French) that share spelling with English.
                "network_wifi",
                "network_roaming",
                "media_type_images",
                "media_type_audio",
                "media_type_video",
                "media_type_documents",
                // Shared-media tab labels that are loan-words / spelled the same
                // in some locales ("Images" in French, "Videos" in German).
                "shared_media_tab_images",
                "shared_media_tab_videos",
                // Call quick-action labels: "Audio"/"Video" are loan words
                // spelled identically in several locales (DE/ES/FR/IT).
                "quick_action_audio",
                "quick_action_video",
                "app_self_update_resolving",
                "app_self_update_resolving_body",
                "app_self_update_confirm_title",
                "app_self_update_confirm_message",
                "app_self_update_download",
                "app_self_update_downloading",
                "app_self_update_download_progress",
                "app_self_update_download_progress_unknown_total",
                "app_self_update_cancel",
                "app_self_update_retry",
                "app_self_update_install",
                "app_self_update_ready_title",
                "app_self_update_ready_message",
                "app_self_update_hash_mismatch",
                "app_self_update_download_failed",
                "app_self_update_resolve_failed",
                "app_self_update_no_asset",
                "app_self_update_install_failed",
                "app_self_update_permission_title",
                "app_self_update_permission_message",
                "app_self_update_open_settings",
                "app_self_update_error_title",
                "app_self_update_size_unknown",
            )

        // Exemptions that are valid only for SPECIFIC locales, keyed by the
        // `values-<locale>` directory name. Unlike [identicalValueAllowedKeys]
        // (which waives the copied-English check for every locale), these waive
        // it only where the match is legitimate — so a regression that copies
        // English into a different locale for the same key still fails.
        //
        // "minute(s)" is spelled identically in French: the unit label, the
        // custom-duration format, and the "5 minutes" preset legitimately match
        // English there — but nowhere else (de "Minuten", es "minutos", …).
        val localeScopedAllowedKeys: Map<String, Set<String>> =
            mapOf(
                "values-de" to
                    setOf(
                        // "Name" is the German word for "name".
                        "profile_contact_name_hint",
                        // "Album" is the German word for "album", and the
                        // counted-preview format is punctuation-only here.
                        "media_album",
                        "media_counted_format",
                        "chat_folder_name_label",
                        // Standard German technical/file-type terms.
                        "attachment_type_text",
                        "attachment_type_code",
                        "attachment_type_audio",
                        "attachment_type_video",
                    ),
                "values-es" to
                    setOf(
                        // "chat"/"chats" are common loan words in Spanish.
                        "archived_chats_count",
                        "chat_folder_chat_count",
                        // The counted-preview format is punctuation-only here.
                        "media_counted_format",
                        // "Audio" is the standard Spanish media-type term.
                        "attachment_type_audio",
                    ),
                "values-fr" to
                    setOf(
                        "disappearing_unit_minutes",
                        "disappearing_minutes_format",
                        "disappearing_5_minutes",
                        // "Contacts" is spelled identically in French.
                        "contacts",
                        // "Contact" is spelled identically in French.
                        "attach_contact",
                        // "Description" is spelled identically in French.
                        "chat_folder_description_label",
                        // "Album" is spelled identically in French, and the
                        // counted-preview format is punctuation-only here.
                        "media_album",
                        "media_counted_format",
                        // Standard French file-type terms/loan words.
                        "attachment_type_archive",
                        "attachment_type_document",
                        "attachment_type_code",
                        "attachment_type_audio",
                        "attachment_type_image",
                    ),
                "values-it" to
                    setOf(
                        // "chat" is a common loan word in Italian.
                        "archived_chats_count",
                        "chat_folder_chat_count",
                        // "Album" is spelled identically in Italian, and the
                        // counted-preview format is punctuation-only here.
                        "media_album",
                        "media_counted_format",
                        // Standard Italian file-type terms/loan words.
                        "attachment_type_audio",
                        "attachment_type_video",
                        "attachment_type_file",
                        // "Video" is also the established Italian event-card term.
                        "nostr_event_type_video",
                    ),
                "values-pt" to
                    setOf(
                        // The counted-preview format is punctuation-only here.
                        "media_counted_format",
                    ),
                "values-ru" to
                    setOf(
                        // The counted-preview format is punctuation-only here.
                        "media_counted_format",
                    ),
                "values-tr" to
                    setOf(
                        // The counted-preview format is punctuation-only here.
                        "media_counted_format",
                        // "Video" is the standard Turkish media-type term.
                        "attachment_type_video",
                        "nostr_event_type_video",
                    ),
            )
    }
}
