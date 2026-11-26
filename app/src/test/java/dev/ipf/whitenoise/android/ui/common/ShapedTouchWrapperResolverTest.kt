package dev.ipf.whitenoise.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** Rule 5's wrapper calls resolve through Kotlin's scopes and argument mapping, failing closed where text cannot. */
class ShapedTouchWrapperResolverTest {
    /** A wrapper call matches the function the caller's imports or package reach, not any same-named one. */
    @Test
    fun crossFileWrappersResolveByImport() {
        val files =
            mapOf(
                "ImportsB.kt" to "package c\nimport b.ActionButton\n$WRAPPER_CALL",
                "ImportsA.kt" to "package d\nimport a.ActionButton\n$WRAPPER_CALL",
                "Aliased.kt" to
                    "package e\nimport a.ActionButton as Act\n${WRAPPER_CALL.replace("ActionButton", "Act")}",
                "SamePackage.kt" to "package a\n\n$WRAPPER_CALL",
                "Star.kt" to "package g\nimport a.*\n$WRAPPER_CALL",
                "StarAndExplicit.kt" to "package h\nimport a.*\nimport b.ActionButton\n\n$WRAPPER_CALL",
                "Unreached.kt" to "package i\n\n$WRAPPER_CALL",
                "AliasOnly.kt" to "package w\nimport a.ActionButton as Act\n$WRAPPER_CALL",
            )
        assertWrapperCalls(
            files,
            reached = listOf("ImportsA.kt", "Aliased.kt", "SamePackage.kt", "Star.kt"),
            unreached = listOf("ImportsB.kt", "StarAndExplicit.kt", "Unreached.kt", "AliasOnly.kt"),
        )
    }

    /**
     * Kotlin skips a function the call cannot apply to, so an own or local overload with other parameters does not
     * hide the wrapper, while one with the wrapper's parameter types, or one the wrapper cannot take the call from,
     * does. A local function counts only in its scope after its declaration.
     */
    @Test
    fun wrapperCallsSkipOverloadsTheyCannotApplyTo() {
        val call = WRAPPER_CALL
        val files =
            mapOf(
                "OwnTopLevel.kt" to "package a\nfun ActionButton() {}\n$call",
                "OwnOverload.kt" to "package p\nimport a.*\n$call\nfun ActionButton(count: Int) {}",
                "OwnApplies.kt" to
                    "package o\nimport a.*\n$call\nfun ActionButton(onClick: () -> Unit, modifier: Modifier) {}",
                "NamedMismatch.kt" to
                    "package t\nimport a.*\nActionButton(onClick = go, modifier = Modifier.width(36.dp))\n" +
                    "fun ActionButton(onClick: () -> Unit, label: String) {}",
                "TrailingLambda.kt" to
                    "package u\nimport a.*\nActionButton(go, Modifier.width(36.dp)) {}\n" +
                    "fun ActionButton(onClick: () -> Unit, modifier: Modifier, content: () -> Unit) {}",
                "LocalOverload.kt" to
                    "package q\nimport a.ActionButton\nfun Screen() { fun ActionButton(count: Int) {}; $call }",
                "LocalLater.kt" to "package r\nimport a.ActionButton\nfun Screen() { $call; fun ActionButton() {} }",
                "LocalClosed.kt" to
                    "package s\nimport a.ActionButton\nfun Screen() { if (x) { fun ActionButton(onClick: () -> Unit, " +
                    "modifier: Modifier) {} }; $call }",
                "LocalElsewhere.kt" to "package j\nimport a.ActionButton\n$call\nfun Other() { fun ActionButton() {} }",
                "LocalStar.kt" to "package k\nimport a.*\n$call\nfun Other() { fun ActionButton() {} }",
            )
        val reached =
            listOf(
                "OwnTopLevel.kt",
                "OwnOverload.kt",
                "NamedMismatch.kt",
                "LocalOverload.kt",
                "LocalLater.kt",
                "LocalClosed.kt",
                "LocalElsewhere.kt",
                "LocalStar.kt",
            )
        assertWrapperCalls(files, reached, unreached = listOf("OwnApplies.kt", "TrailingLambda.kt"))
    }

    /**
     * Where the text cannot prove Kotlin would stop at an overload, the wrapper stays reachable and is reported: other
     * parameter types (argument types are not read), a receiver function, a vararg, a positional argument after a
     * named one, a required parameter behind an annotation's `=`, an escaped argument name such as `when`, or a
     * trailing lambda into a non-function slot.
     */
    @Test
    fun uncertainOverloadsLeaveTheWrapperReachable() {
        val files =
            mapOf(
                "Vararg.kt" to "package v\nimport a.*\n$WRAPPER_CALL\nfun ActionButton(vararg parts: Any) {}",
                "TypeMismatch.kt" to
                    "package x\nimport a.ActionButton\nfun Screen() { fun ActionButton(onClick: () -> Unit, " +
                    "modifier: String) {}; $WRAPPER_CALL }",
                "PackageMismatch.kt" to
                    "package y\nimport a.*\n$WRAPPER_CALL\nfun ActionButton(onClick: () -> Unit, modifier: String) {}",
                "ReceiverOverload.kt" to
                    "package rr\nimport a.*\n$WRAPPER_CALL\n" +
                    "fun RowScope.ActionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {}",
                // A wrapper whose modifier has no default, and one with a defaulted count.
                "Plain.kt" to
                    "package c2\nfun Plain(onClick: () -> Unit, modifier: Modifier) " +
                    "{ IconButton(onClick, modifier) {} }",
                "Counted.kt" to
                    "package d2\nfun Counted(onClick: () -> Unit, modifier: Modifier, count: Int = 0) " +
                    "{ IconButton(onClick, modifier) {} }",
                "MixedArguments.kt" to
                    "package ma\nimport c2.*\nPlain(onClick = go, Modifier.width(36.dp))\n" +
                    "fun Plain(onClick: () -> Unit, modifier: String = \"\") {}",
                "AnnotatedRequired.kt" to
                    "package an\nimport d2.Counted\nfun Screen() { fun Counted(onClick: () -> Unit, " +
                    "modifier: Modifier, @Hint(enabled = true) count: Int) {}; Counted(go, Modifier.width(36.dp)) }",
                "NestedAnnotation.kt" to
                    "package na\nimport d2.Counted\nfun Screen() { fun Counted(onClick: () -> Unit, " +
                    "modifier: Modifier, @Hint(values = arrayOf(x = 1)) count: Int) {}; " +
                    "Counted(go, Modifier.width(36.dp)) }",
                "GroupedAnnotation.kt" to
                    "package ga\nimport d2.Counted\nfun Screen() { fun Counted(onClick: () -> Unit, " +
                    "modifier: Modifier, @[Hint(x = 1) Stable] count: Int) {}; " +
                    "Counted(go, Modifier.width(36.dp)) }",
                // An escaped name is still a named argument, and the local overload has no such parameter.
                "Keyword.kt" to
                    "package e2\nfun Tagged(onClick: () -> Unit, modifier: Modifier, `when`: Int = 0) " +
                    "{ IconButton(onClick, modifier) {} }",
                "EscapedName.kt" to
                    "package es\nimport e2.Tagged\nfun Screen() { fun Tagged(onClick: () -> Unit, " +
                    "modifier: Modifier, count: Int = 0) {}; Tagged(go, Modifier.width(36.dp), `when` = 1) }",
                // A named argument for a slot already filled by position rules the local overload out.
                "Extra.kt" to
                    "package x2\nfun Tagged2(onClick: () -> Unit, modifier: Modifier, extra: Modifier = Modifier) " +
                    "{ IconButton(onClick, modifier) {} }",
                "DuplicateSlot.kt" to
                    "package dd\nimport x2.Tagged2\nfun Screen() { fun Tagged2(onClick: () -> Unit, extra: Modifier, " +
                    "modifier: Modifier = Modifier) {}; Tagged2(go, Modifier.width(36.dp), extra = Modifier) }",
                // A named argument for the slot the trailing lambda fills rules the local overload out too.
                "Framed.kt" to
                    "package f2\nfun Framed(onClick: () -> Unit, modifier: Modifier, content: () -> Unit, " +
                    "after: () -> Unit) { IconButton(onClick, modifier) {} }",
                "LambdaSlotTwice.kt" to
                    "package ls\nimport f2.Framed\nfun Screen() { fun Framed(onClick: () -> Unit, " +
                    "modifier: Modifier, after: () -> Unit = {}, content: () -> Unit) {}; " +
                    "Framed(go, Modifier.width(36.dp), content = {}) {} }",
                "Slotted.kt" to
                    "package sl\nfun Slotted(onClick: () -> Unit, modifier: Modifier, content: () -> Unit) " +
                    "{ IconButton(onClick, modifier) {} }",
                "LambdaIntoInt.kt" to
                    "package z\nimport sl.*\nSlotted(go, Modifier.width(36.dp)) {}\n" +
                    "fun Slotted(onClick: () -> Unit, modifier: Modifier, count: Int) {}",
            )
        assertWrapperCalls(files, reached = files.keys.filterNot { it in WRAPPER_DEFINITIONS }, unreached = emptyList())
    }

    /**
     * A comparison in a default value, or a type argument list with commas in a type or a default, never merges a
     * wrapper's parameters, so its `modifier` parameter is still found and its sized calls are reported.
     */
    @Test
    fun defaultValuesNeverMergeParameters() {
        val handOff = "{ IconButton(onClick, modifier) {} }"
        val wrappers =
            listOf(
                "fun Wide(onClick: () -> Unit, isWide: Boolean = width > 600, modifier: Modifier = Modifier)",
                "fun Narrow(onClick: () -> Unit, enabled: Boolean = count < max, modifier: Modifier = Modifier)",
                "fun Keyed(onClick: () -> Unit, values: Map<String, Int> = mapOf<String, Int>(), modifier: Modifier)",
                "fun First(enabled: Boolean = 1 < 2, wide: Boolean = 2 > 1, onClick: () -> Unit, modifier: Modifier)",
                "fun Bounds(low: Boolean = a <= b, high: Boolean = a >= b, onClick: () -> Unit, modifier: Modifier)",
                "fun Annotated(onClick: () -> Unit, values: Map<@Ann(x = 1) String, Int>, modifier: Modifier)",
                "fun EscapedType(onClick: () -> Unit, labels: Map<`when`, Int>, modifier: Modifier)",
                "fun AnnotatedDefault(onClick: () -> Unit, " +
                    "values: Map<String, Int> = mapOf<@Ann(x = 1) String, Int>(), modifier: Modifier)",
            ).joinToString("\n", prefix = "package df\n") { "$it $handOff" }
        val calls =
            mapOf(
                "WideNamed.kt" to "Wide(go, modifier = Modifier.width(36.dp))",
                "WidePositional.kt" to "Wide(go, true, Modifier.width(36.dp))",
                "Narrow.kt" to "Narrow(go, modifier = Modifier.width(36.dp))",
                "Keyed.kt" to "Keyed(go, modifier = Modifier.width(36.dp))",
                "First.kt" to "First(onClick = go, modifier = Modifier.width(36.dp))",
                "Bounds.kt" to "Bounds(onClick = go, modifier = Modifier.width(36.dp))",
                "Annotated.kt" to "Annotated(go, emptyMap(), Modifier.width(36.dp))",
                "EscapedType.kt" to "EscapedType(go, emptyMap(), Modifier.width(36.dp))",
                "AnnotatedDefault.kt" to "AnnotatedDefault(go, emptyMap(), Modifier.width(36.dp))",
            ).mapValues { (_, call) -> "package dc\nimport df.*\nfun Screen() { $call }" }
        val findings = stretchedWrapperFindings(calls + ("Defaults.kt" to wrappers))
        for (file in calls.keys) assertEquals(file, listOf("$STRETCHED_ICON_BUTTON @3"), findings.getValue(file))
        assertEquals(
            listOf("onClick: () -> Unit", "values: Map<String, Int> = mapOf<String, Int>()", "modifier: Modifier"),
            parameterList("onClick: () -> Unit, values: Map<String, Int> = mapOf<String, Int>(), modifier: Modifier")
                .map(String::trim),
        )
    }

    /**
     * A generic wrapper is a wrapper, whether its call infers or spells out the type arguments, even with arrows or
     * deep nesting in them; a labeled or next-line trailing lambda still fills the last slot; and whitespace before
     * a default's type arguments keeps its commas inside one parameter.
     */
    @Test
    fun genericWrappersAndSpacedTypeArgumentsAreRead() {
        val handOff = "{ IconButton(onClick, modifier) {} }"
        val wrappers =
            listOf(
                "fun <T> Gen(onClick: () -> Unit, modifier: Modifier, value: T)",
                "fun <T : kotlin.Any> Bound(onClick: () -> Unit, modifier: Modifier, value: T)",
                "fun <T : () -> Unit> Arrow(onClick: () -> Unit, modifier: Modifier, value: T)",
                "fun <T : kotlin.collections.MutableMap<String, List<Map<String, Int>>>> " +
                    "Long(modifier: Modifier, v: T)",
                "fun Slot(onClick: () -> Unit, modifier: Modifier, content: () -> Unit)",
                "fun Spaced(onClick: () -> Unit, values: Map<String, Int> = mapOf <String, Int>(), modifier: Modifier)",
                "fun <T,> Trailing(onClick: () -> Unit, modifier: Modifier, value: T)",
            ).joinToString("\n", prefix = "package gw\n") { "$it $handOff" }
        val calls =
            mapOf(
                "Inferred.kt" to "Gen(go, Modifier.width(36.dp), 1)",
                "Explicit.kt" to "Gen<Int>(go, Modifier.width(36.dp), 1)",
                "Bound.kt" to "Bound<String>(go, Modifier.width(36.dp), \"x\")",
                "Arrow.kt" to "Arrow<() -> Unit>(go, Modifier.width(36.dp), {})",
                "Deep.kt" to "Gen<Map<String, List<List<Int>>>>(go, Modifier.width(36.dp), emptyMap())",
                "Labeled.kt" to "Slot(go, Modifier.width(36.dp)) content@{}",
                "NextLine.kt" to "Slot(go, Modifier.width(36.dp))\n{}",
                "LongBound.kt" to "Long(Modifier.width(36.dp), mutableMapOf())",
                "Spaced.kt" to "Spaced(go, emptyMap(), Modifier.width(36.dp))",
                "TrailingComma.kt" to "Trailing<Int,>(go, Modifier.width(36.dp), 1)",
                "TrailingInferred.kt" to "Trailing(go, Modifier.width(36.dp), 1)",
                "TrailingDeclarationOnly.kt" to "Trailing<Int>(go, Modifier.width(36.dp), 1)",
                "TrailingCallOnly.kt" to "Gen<Int,>(go, Modifier.width(36.dp), 1)",
            ).mapValues { (_, call) -> "package gc\nimport gw.*\nfun Screen() { $call }" }
        val findings = stretchedWrapperFindings(calls + ("Generic.kt" to wrappers))
        for (file in calls.keys) assertEquals(file, listOf("$STRETCHED_ICON_BUTTON @3"), findings.getValue(file))
    }

    /** A wrapper overload the call cannot apply to never reports it; the overload that takes the call decides. */
    @Test
    fun inapplicableWrapperOverloadsAreSkipped() {
        val overloads =
            "package ov\n" +
                "fun Chip(onClick: () -> Unit, modifier: Modifier) { IconButton(onClick, modifier) {} }\n" +
                "fun Chip(onClick: () -> Unit, modifier: Modifier, variant: Int) " +
                "{ SlotIconButton(onClick, modifier) {} }"
        val calls =
            mapOf(
                "Variant.kt" to "Chip(go, Modifier.width(36.dp), variant = 1)",
                "Plain.kt" to "Chip(go, Modifier.width(36.dp))",
            ).mapValues { (_, call) -> "package oc\nimport ov.Chip\nfun Screen() { $call }" }
        val findings = stretchedWrapperFindings(calls + ("Chips.kt" to overloads))
        assertEquals(emptyList<String>(), findings.getValue("Variant.kt"))
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @3"), findings.getValue("Plain.kt"))
    }

    /**
     * Explicit type arguments reach only the overloads with that many type parameters, so `Chip<Int>(…)` takes the
     * generic overload and never reports the non-generic one beside it, whichever of the two wraps `IconButton`.
     */
    @Test
    fun explicitTypeArgumentsSkipOverloadsWithOtherTypeParameterCounts() {
        val plain = "fun Chip(onClick: () -> Unit, modifier: Modifier) { BUTTON(onClick, modifier) {} }"
        val generic = "fun <T> Chip(onClick: () -> Unit, modifier: Modifier) { BUTTON(onClick, modifier) {} }"
        val slotGeneric =
            "package gs\n" + plain.replace("BUTTON", "IconButton") + "\n" + generic.replace("BUTTON", "SlotIconButton")
        val iconGeneric =
            "package gi\n" + plain.replace("BUTTON", "SlotIconButton") + "\n" + generic.replace("BUTTON", "IconButton")
        val calls =
            mapOf(
                "SlotExplicit.kt" to ("gs" to "Chip<Int>(go, Modifier.width(36.dp))"),
                "IconExplicit.kt" to ("gi" to "Chip<Int>(go, Modifier.width(36.dp))"),
                "TwoArguments.kt" to ("gi" to "Chip<Int, String>(go, Modifier.width(36.dp))"),
            ).mapValues { (_, call) -> "package gc\nimport ${call.first}.Chip\nfun Screen() { ${call.second} }" }
        val findings = stretchedWrapperFindings(calls + ("Slot.kt" to slotGeneric) + ("Icon.kt" to iconGeneric))
        assertEquals(emptyList<String>(), findings.getValue("SlotExplicit.kt"))
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @3"), findings.getValue("IconExplicit.kt"))
        assertEquals(emptyList<String>(), findings.getValue("TwoArguments.kt"))
    }

    /** Kotlin's trailing comma ends a type list without adding an entry, at any depth and around arrows. */
    @Test
    fun typeArgumentCountIgnoresTrailingCommas() {
        val counts =
            mapOf(
                "<Int>" to 1,
                "<Int,>" to 1,
                "<Int , >" to 1,
                "<A, B,>" to 2,
                "<Map<String, Int>,>" to 1,
                "<T : () -> Unit,>" to 1,
                "<T : Comparable<T>, R,>" to 2,
            )
        for ((list, count) in counts) assertEquals(list, count, list.typeArgumentCount())
        assertEquals(null, "Int".typeArgumentCount())
    }

    /** A local function shadows an imported wrapper at the calls in its scope, and a local wrapper still counts. */
    @Test
    fun localFunctionsShadowImportedWrappers() {
        val slot = "fun ActionButton(onClick: () -> Unit, modifier: Modifier) { SlotIconButton(onClick, modifier) {} }"
        val screen = "fun Screen() {\n$slot\nActionButton(go, Modifier.width(36.dp))\n}"
        val shadowed = "package m\nimport a.ActionButton\n$screen"
        val localWrapper = "package n\n\n" + screen.replace("SlotIconButton", "IconButton")
        val local = stretchedWrapperFindings(WRAPPER_FILES + ("Shadowed.kt" to shadowed) + ("Local.kt" to localWrapper))
        assertEquals(emptyList<String>(), local.getValue("Shadowed.kt"))
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @5"), local.getValue("Local.kt"))
    }

    /** Checks each of [files], beside the two wrapper packages, for a stretched call on line 3 or none. */
    private fun assertWrapperCalls(
        files: Map<String, String>,
        reached: List<String>,
        unreached: List<String>,
    ) {
        val findings = stretchedWrapperFindings(WRAPPER_FILES + files)
        for (file in reached) assertEquals(file, listOf("$STRETCHED_ICON_BUTTON @3"), findings.getValue(file))
        for (file in unreached) assertEquals(file, emptyList<String>(), findings.getValue(file))
    }

    /** A same-file wrapper call reaches the local function in scope first, else the top-level one. */
    @Test
    fun sameFileWrapperCallsResolveToTheFunctionInScope() {
        val iconButton = "fun Action(onTap: () -> Unit, modifier: Modifier) { IconButton(onTap, modifier) {} }"
        val slot = iconButton.replace("IconButton", "SlotIconButton")
        val call = "Action(go, Modifier.width(36.dp))"
        val file = { topLevel: String, local: String ->
            "private $topLevel\nfun Screen() {\n    $local\n    $call\n}\nfun Other() { $call }"
        }
        val localSlot = stretchedWrapperFindings(mapOf("F.kt" to file(iconButton, slot)))
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @6"), localSlot.getValue("F.kt"))
        val localWrapper = stretchedWrapperFindings(mapOf("F.kt" to file(slot, iconButton)))
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @4"), localWrapper.getValue("F.kt"))
    }

    /** An escaped argument name is the same name to every rule, so backticks never hide a modifier or shape. */
    @Test
    fun escapedArgumentNamesReadAsPlainOnes() {
        val call = "fun Screen() { ActionButton(go, `modifier` = Modifier.width(36.dp)) }"
        val escapedWrapper =
            "package ew\nfun ActionButton(onClick: () -> Unit, `modifier`: Modifier) " +
                "{ IconButton(onClick, `modifier`) {} }"
        val files =
            WRAPPER_FILES +
                ("Escaped.kt" to "package q2\nimport a.ActionButton\n$call") +
                ("EscapedWrapper.kt" to escapedWrapper) +
                ("CallsEscaped.kt" to "package q3\nimport ew.ActionButton\n${call.replace("`modifier`", "modifier")}")
        val findings = stretchedWrapperFindings(files)
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @3"), findings.getValue("Escaped.kt"))
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @3"), findings.getValue("CallsEscaped.kt"))
        // An annotation with arguments before the wrapper's modifier parameter still leaves it a wrapper.
        val suppressed = "@SuppressLint(\"ModifierParameter\") modifier: Modifier"
        val annotated = escapedWrapper.replace("`modifier`: Modifier", suppressed)
        val withAnnotated = stretchedWrapperFindings(files + ("EscapedWrapper.kt" to annotated))
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @3"), withAnnotated.getValue("CallsEscaped.kt"))
        val annotations =
            listOf(
                "@Hint(values = arrayOf(1)) @Stable modifier: Modifier",
                "@[Hint Stable] modifier: Modifier",
                "@param:[Hint(x = 1)] @param:Stable modifier: Modifier",
                "@`class` modifier: Modifier",
                "@pkg.`class`(x = 1) @param:`Hint` modifier: Modifier",
                "@pkg .Hint @Å modifier: Modifier",
                "@pkg\n    .Hint vararg modifier: Modifier",
                "@Hint(A > B) modifier: Modifier = Modifier",
                "@Hint(1 < 2) @Other(x >= y) modifier: Map<String, List<Int>> = emptyMap()",
                "@Flag(1 < 2) modifier: Modifier, extra: Int = 0",
            )
        for (annotated in annotations) {
            val wrapper = escapedWrapper.replace("`modifier`: Modifier", annotated)
            val withAnnotations = stretchedWrapperFindings(files + ("EscapedWrapper.kt" to wrapper))
            assertEquals(annotated, listOf("$STRETCHED_ICON_BUTTON @3"), withAnnotations.getValue("CallsEscaped.kt"))
        }
        val surfaces =
            listOf(
                "Surface(shape = CircleShape, `modifier` = Modifier.clickable(onClick = go)) {}",
                "Surface(`shape` = CircleShape, modifier = Modifier.clickable(onClick = go)) {}",
            )
        for (surface in surfaces) {
            assertEquals(surface, listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surface))
        }
    }

    private companion object {
        /** The fixtures that only define wrappers for the calls around them. */
        val WRAPPER_DEFINITIONS = setOf("Plain.kt", "Counted.kt", "Keyword.kt", "Extra.kt", "Framed.kt", "Slotted.kt")

        const val WRAPPER_SIGNATURE = "fun ActionButton(onClick: () -> Unit, modifier: Modifier = Modifier)"
        const val WRAPPER_CALL = "fun Screen() { ActionButton(go, Modifier.width(36.dp)) }"

        /** `a.ActionButton` wraps a plain IconButton, `b.ActionButton` a slot-aware one with the same signature. */
        val WRAPPER_FILES =
            mapOf(
                "A.kt" to "package a\n$WRAPPER_SIGNATURE { IconButton(onClick, modifier) {} }",
                "B.kt" to "package b\n$WRAPPER_SIGNATURE { SlotIconButton(onClick, modifier) {} }",
            )
    }
}
