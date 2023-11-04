package com.keevault.flutter_autofill_service

import org.tinylog.Level
import org.tinylog.core.TinylogLoggingProvider
import org.tinylog.format.MessageFormatter

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
            super.log(depth + 1, tag, level, exception, formatter, obj, arguments ?: emptyArray<Object>())
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
            super.log(loggerClassName, tag, level, exception, formatter, obj, arguments ?: emptyArray<Object>())
        }
    }

}
