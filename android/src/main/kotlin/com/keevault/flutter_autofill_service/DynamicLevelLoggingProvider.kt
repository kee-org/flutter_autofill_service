package com.keevault.flutter_autofill_service

import org.tinylog.Level
import org.tinylog.core.TinylogLoggingProvider
import org.tinylog.format.MessageFormatter
import org.tinylog.provider.InternalLogger
import org.tinylog.provider.ProviderRegistry

class DynamicLevelLoggingProvider : TinylogLoggingProvider() {

    @Volatile
    var activeLevel: Level = Level.TRACE;

    override fun isEnabled(depth: Int, tag: String?, level: Level?): Boolean {
        return activeLevel <= level && super.isEnabled(depth + 1, tag, level)
    }

    override fun log(
        depth: Int,
        tag: String?,
        level: Level?,
        exception: Throwable?,
        formatter: MessageFormatter?,
        obj: Any?,
        arguments: Array<Any>?
    ) {
        if (activeLevel <= level) {
            try {
                super.log(depth + 1, tag, level, exception, formatter, obj, arguments ?: emptyArray<Any>())
            } catch (t: Exception) {
                // Some third-party callers (e.g. other libraries' embedded SLF4J-based servers) exercise
                // argument/formatter combinations that trigger bugs in tinylog's own formatting code. A
                // failure while writing a log entry must never be allowed to crash the host app/service.
                InternalLogger.log(Level.ERROR, t, "Failed to write log entry")
            }
        }
    }

    override fun log(
        loggerClassName: String?,
        tag: String?,
        level: Level?,
        exception: Throwable?,
        formatter: MessageFormatter?,
        obj: Any?,
        arguments: Array<Any>?
    ) {
        if (activeLevel <= level) {
            try {
                super.log(loggerClassName, tag, level, exception, formatter, obj, arguments ?: emptyArray<Any>())
            } catch (t: Exception) {
                InternalLogger.log(Level.ERROR, t, "Failed to write log entry")
            }
        }
    }

    companion object {
        /**
         * Applies [level] to the app's currently registered [DynamicLevelLoggingProvider], if there is one.
         *
         * tinylog silently falls back to its own default provider when a consuming app hasn't wired this
         * provider up via tinylog.properties/META-INF services, so callers must not assume the request will
         * succeed (see README's Logging section).
         */
        fun setActiveLevel(level: Level) {
            (ProviderRegistry.getLoggingProvider() as? DynamicLevelLoggingProvider)?.activeLevel = level
        }
    }

}
