package io.crates.keyring

import android.content.Context

class Keyring {
    companion object {
        init {
            System.loadLibrary("marmot_uniffi")
        }

        external fun initializeNdkContext(context: Context)
    }
}
