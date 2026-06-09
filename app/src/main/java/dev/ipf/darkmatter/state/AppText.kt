package dev.ipf.darkmatter.state

import android.content.Context
import androidx.annotation.StringRes

sealed interface AppText {
    fun resolve(context: Context): String

    data class Plain(
        val value: String,
    ) : AppText {
        override fun resolve(context: Context): String = value
    }

    data class Resource(
        @param:StringRes val resId: Int,
        val args: List<Any> = emptyList(),
    ) : AppText {
        override fun resolve(context: Context): String = if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args.toTypedArray())
    }
}
