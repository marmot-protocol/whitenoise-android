package dev.ipf.whitenoise.android.state

import org.json.JSONArray
import org.json.JSONObject

/** The demo turn's words, files and item bodies, kept apart from the order the script plays them in. */
internal object AgentDemoScriptContent {
    fun sessionBody() =
        JSONObject()
            .put("harness_version", "demo")
            .put("settings", JSONArray().put(JSONObject().put("id", "model").put("label", "Model").put("value", MODEL)))

    fun approvalBody(): JSONObject {
        val choices = JSONArray()
        listOf("c0" to "Allow once", "c1" to "Allow, and don't ask again", DENY to "Deny").forEach { (id, label) ->
            val effect = if (id == DENY) "deny" else "allow"
            choices.put(JSONObject().put("id", id).put("label", label).put("effect", effect))
        }
        return JSONObject().put("subject", "tool:run").put("choices", choices)
    }

    fun questionBody(): JSONObject {
        val options = JSONArray()
        WORD_OPTIONS.forEach { (id, word) ->
            options.put(JSONObject().put("id", id).put("label", word).put("description", "Return \"$word, Ada!\""))
        }
        val asked =
            JSONObject()
                .put("id", WORD_QUESTION_ID)
                .put("header", "Farewell word")
                .put("text", WORD_QUESTION)
                .put("multiple", false)
                .put("allow_text", true)
                .put("options", options)
        return JSONObject().put("answer_via", "request").put("questions", JSONArray().put(asked))
    }

    fun farewellDiff(word: String) =
        "@@ -1,6 +1,10 @@\n def greet(name):\n     return f\"Hello, {name}!\"\n \n+\n+def farewell(name):\n" +
            "+    return f\"$word, {name}!\"\n+\n \n if __name__ == \"__main__\":\n     print(greet(\"Ada\"))\n" +
            "+    print(farewell(\"Ada\"))\n"

    fun ranReply(word: String) =
        "Added `farewell(name)` to `greet.py`, and the script now prints both lines:\n\n" +
            "```text\nHello, Ada!\n$word, Ada!\n```"

    fun notRunReply(word: String) =
        "Added `farewell(name)` to `greet.py`; it returns \"$word, {name}!\". I didn't run the script, " +
            "so run `python3 greet.py` to check it."

    const val SESSION_ITEM = "session:demo"
    const val MODEL = "demo-model"
    const val PROMPT = "Add a farewell function to greet.py and check that it works."
    const val THINKING =
        "greet.py has one function, greet(name), called from main. I'll add farewell(name) next to it in the same " +
            "style and print both from main."
    const val CWD = "~/projects/greeter"
    const val GREET_PATH = "greet.py"
    const val GREET_PY =
        "1\tdef greet(name):\n2\t    return f\"Hello, {name}!\"\n3\t\n4\t\n5\tif __name__ == \"__main__\":\n" +
            "6\t    print(greet(\"Ada\"))\n"
    const val TEST_COMMAND = "/usr/bin/zsh -lc 'python3 -m pytest -q'"
    const val NO_TESTS_EXIT = 5
    const val TEST_DURATION_MS = 1_380L
    const val NO_TESTS_OUTPUT = "\nno tests ran in 0.02s\n"
    const val NO_TESTS_REPLY = "There are no tests yet, so I'll run the script itself."
    const val RUN_COMMAND = "/usr/bin/zsh -lc 'python3 greet.py'"
    const val RUN_DURATION_MS = 412L
    const val DENY = "c2"
    const val WORD_QUESTION_ID = "farewell_word"
    const val WORD_QUESTION = "Which word should farewell(name) use?"
    const val GOODBYE = "Goodbye"
    val WORD_OPTIONS = listOf("o0" to GOODBYE, "o1" to "Bye", "o2" to "See you")
    val PLAN_STEPS = listOf("Read greet.py", "Add farewell(name)", "Run the tests", "Run the script")
}
