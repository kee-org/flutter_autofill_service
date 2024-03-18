package com.keevault.flutter_autofill_service

import org.tinylog.Level
import org.tinylog.core.TinylogLoggingProvider
import org.tinylog.format.MessageFormatter

class DynamicLevelLoggingProvider(val _logger : TinylogLoggingProvider) {
    private var logger : TinylogLoggingProvider

    init {
        this.logger = _logger
    }

    @Volatile
    var activeLevel: Level = Level.TRACE;

    fun isEnabled(depth: Int, tag: String?, level: Level?): Boolean {
        return activeLevel <= level && this.logger.isEnabled(depth + 1, tag, level)
    }

    fun log(
        depth: Int,
        tag: String?,
        level: Level?,
        exception: Throwable?,
        formatter: MessageFormatter?,
        obj: Any?,
        arguments: Array<Any>?
    ) {
        if (activeLevel <= level) {
            this.logger.log(depth + 1, tag, level, exception, formatter, obj, arguments ?: emptyArray<Any>())
        }
    }

    fun log(
        loggerClassName: String?,
        tag: String?,
        level: Level?,
        exception: Throwable?,
        formatter: MessageFormatter?,
        obj: Any?,
        arguments: Array<Any>?
    ) {
        if (activeLevel <= level) {
            this.logger.log(loggerClassName, tag, level, exception, formatter, obj, arguments ?: emptyArray<Any>())
        }
    }

}
