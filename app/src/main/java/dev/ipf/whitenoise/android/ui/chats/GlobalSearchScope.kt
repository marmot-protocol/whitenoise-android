package dev.ipf.whitenoise.android.ui.chats

/** Conversation scopes use message filters; restored cross-chat chips remain visible for removal. */
internal fun globalSearchVisibleFilterCategories(conversationScope: Boolean): List<GlobalSearchFilterCategory> =
    if (conversationScope) {
        listOf(GlobalSearchFilterCategory.Sender, GlobalSearchFilterCategory.Date, GlobalSearchFilterCategory.Content)
    } else {
        GlobalSearchFilterCategory.entries
    }
