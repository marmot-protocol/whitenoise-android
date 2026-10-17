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
                    fixtureScopeJob(fixtureField(controller, "attachmentPresentation"), "scope"),
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
    val scope = fixtureField(owner, name) as CoroutineScope
    return requireNotNull(scope.coroutineContext[Job])
}

/** Follows the production owner when a scope moves out of the facade. */
private fun fixtureField(owner: Any, name: String): Any =
    requireNotNull(owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner))
