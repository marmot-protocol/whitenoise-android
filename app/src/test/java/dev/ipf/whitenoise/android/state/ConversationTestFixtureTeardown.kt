package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Keeps fixture-owned IO continuations from dispatching after a test replaces Main. */
internal suspend fun clearAndAwaitConversationTestFixture(vararg controllers: ConversationController) {
    val jobs =
        controllers
            .flatMap { controller ->
                listOf(
                    fixtureScopeJob(controller, "controllerScope"),
                    fixtureScopeJob(controller, "inviteStreamScope"),
                    fixtureScopeJob(controller, "attachmentTransferScope"),
                    requireNotNull(controller.appState.mutationsScope.coroutineContext[Job]),
                    fixtureScopeJob(controller.appState, "profileScope"),
                )
            }.distinct()
    controllers.forEach { it.onCleared() }
    // Clear may enqueue editor cleanup. Cancel every producer before awaiting
    // any one of them, while runTest can still pump the installed dispatcher.
    jobs.forEach { it.cancel() }
    jobs.forEach { it.join() }
}

/** Reflection stays inside the fixture rather than exposing production lifecycle internals. */
private fun fixtureScopeJob(
    owner: Any,
    name: String,
): Job {
    val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    val scope = field.get(owner) as CoroutineScope
    return requireNotNull(scope.coroutineContext[Job])
}
