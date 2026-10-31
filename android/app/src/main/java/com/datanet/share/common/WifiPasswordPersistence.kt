package com.datanet.share.common

/**
 * Pure-JVM decision boundary for Android Keystore-backed Wi-Fi password storage.
 * A failed read is not proof that an encrypted record is permanently unusable.
 */
internal object WifiPasswordPersistence {
    data class RestoreResult(val password: String?, val failed: Boolean)

    fun restore(load: () -> String?): RestoreResult =
        try {
            RestoreResult(load(), failed = false)
        } catch (_: Exception) {
            // Never expose Keystore errors or secret material to UI diagnostics.
            RestoreResult(null, failed = true)
        }

    /**
     * Empty means "forget" only after a successful read (including no record).
     * Following a failed read, require an explicitly re-entered password instead.
     */
    fun persistOnStart(
        password: String,
        restoreFailed: Boolean,
        save: (String) -> Unit,
        clear: () -> Unit
    ): Boolean {
        if (restoreFailed && password.isEmpty()) return false
        if (password.isEmpty()) clear() else save(password)
        return true
    }
}
