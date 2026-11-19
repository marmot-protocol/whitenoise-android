package dev.ipf.whitenoise.android.ui.conversation.messages

internal fun FocusedMessageAction.withTerminalCallbacks(close: (() -> Unit) -> Unit): FocusedMessageAction =
    if (!dismissBeforeActivation) {
        this
    } else {
        copy(
            onClick = { close(onClick) },
            onClickAt =
                onClickAt?.let { activate ->
                    { origin -> close { activate(origin) } }
                },
        )
    }
