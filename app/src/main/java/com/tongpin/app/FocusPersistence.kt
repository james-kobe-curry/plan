package com.tongpin.app

import java.io.IOException

internal class FocusPersistenceException(cause: Exception) : IllegalStateException(
    "专注状态未能保存，请检查可用存储空间后重试。", cause,
)

/** SharedPreferences publishes memory before reporting disk failure. Restore that memory too. */
internal fun <T> persistFocusChange(previous: T, commit: () -> Boolean, restore: (T) -> Boolean) {
    val failure = try {
        if (commit()) return
        IOException("专注状态写入失败")
    } catch (failure: Exception) {
        failure
    }
    val reported = FocusPersistenceException(failure)
    try {
        if (!restore(previous)) reported.addSuppressed(IOException("原专注状态暂未写回存储"))
    } catch (restoreFailure: Exception) {
        reported.addSuppressed(restoreFailure)
    }
    // The caller can trigger services, sounds or new UI only after this function returns.
    throw reported
}
