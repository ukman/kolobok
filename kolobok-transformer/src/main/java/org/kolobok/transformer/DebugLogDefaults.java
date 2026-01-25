package org.kolobok.transformer;

import org.kolobok.annotation.DebugLog;

import java.util.Locale;

public final class DebugLogDefaults {
    public static final boolean DEFAULT_LINE_HEAT_MAP = false;
    public static final boolean DEFAULT_LINE_HEAT_MAP_ON_EXCEPTION = false;
    public static final boolean DEFAULT_SUB_HEAT_MAP = false;
    public static final boolean DEFAULT_LOG_DURATION = false;
    public static final boolean DEFAULT_AGGREGATE_CHILDREN = true;
    public static final boolean DEFAULT_LOG_ARGS = true;
    public static final String DEFAULT_MASK = "";
    public static final int DEFAULT_MAX_ARG_LENGTH = 200;
    public static final String DEFAULT_RESULT_MASK = "";
    public static final int DEFAULT_MAX_RESULT_LENGTH = -1;
    public static final DebugLog.LogLevel DEFAULT_LOG_LEVEL = DebugLog.LogLevel.DEBUG;
    public static final DebugLog.LogFormat DEFAULT_LOG_FORMAT = DebugLog.LogFormat.HUMAN;
    public static final boolean DEFAULT_LOG_THREAD_ID = false;
    public static final boolean DEFAULT_LOG_THREAD_NAME = false;
    public static final boolean DEFAULT_LOG_HTTP_REQUEST = false;
    public static final String DEFAULT_TAG = "";
    public static final long DEFAULT_SLOW_THRESHOLD_MS = 0L;
    public static final boolean DEFAULT_LOG_LOCALS = false;
    public static final boolean DEFAULT_LOG_LOCALS_ON_EXCEPTION = false;

    private Boolean lineHeatMap;
    private Boolean lineHeatMapOnException;
    private Boolean subHeatMap;
    private Boolean logDuration;
    private Boolean aggregateChildren;
    private Boolean logArgs;
    private String mask;
    private Integer maxArgLength;
    private String resultMask;
    private Integer maxResultLength;
    private DebugLog.LogLevel logLevel;
    private DebugLog.LogFormat logFormat;
    private Boolean logThreadId;
    private Boolean logThreadName;
    private Boolean logHttpRequest;
    private String tag;
    private Long slowThresholdMs;
    private Boolean logLocals;
    private Boolean logLocalsOnException;

    public static DebugLogDefaults fromSystemEnv() {
        DebugLogDefaults defaults = new DebugLogDefaults();
        defaults.lineHeatMap = readBoolean("kolobok.debuglog.lineHeatMap", "KLB_DEBUGLOG_LINE_HEAT_MAP");
        defaults.lineHeatMapOnException = readBoolean("kolobok.debuglog.lineHeatMapOnException",
                "KLB_DEBUGLOG_LINE_HEAT_MAP_ON_EXCEPTION");
        defaults.subHeatMap = readBoolean("kolobok.debuglog.subHeatMap", "KLB_DEBUGLOG_SUB_HEAT_MAP");
        defaults.logDuration = readBoolean("kolobok.debuglog.logDuration", "KLB_DEBUGLOG_LOG_DURATION");
        defaults.aggregateChildren = readBoolean("kolobok.debuglog.aggregateChildren", "KLB_DEBUGLOG_AGGREGATE_CHILDREN");
        defaults.logArgs = readBoolean("kolobok.debuglog.logArgs", "KLB_DEBUGLOG_LOG_ARGS");
        defaults.mask = readString("kolobok.debuglog.mask", "KLB_DEBUGLOG_MASK");
        defaults.maxArgLength = readInt("kolobok.debuglog.maxArgLength", "KLB_DEBUGLOG_MAX_ARG_LENGTH");
        defaults.resultMask = readString("kolobok.debuglog.resultMask", "KLB_DEBUGLOG_RESULT_MASK");
        defaults.maxResultLength = readInt("kolobok.debuglog.maxResultLength", "KLB_DEBUGLOG_MAX_RESULT_LENGTH");
        defaults.logLevel = readEnum(DebugLog.LogLevel.class, "kolobok.debuglog.logLevel", "KLB_DEBUGLOG_LOG_LEVEL");
        defaults.logFormat = readEnum(DebugLog.LogFormat.class, "kolobok.debuglog.logFormat", "KLB_DEBUGLOG_LOG_FORMAT");
        defaults.logThreadId = readBoolean("kolobok.debuglog.logThreadId", "KLB_DEBUGLOG_LOG_THREAD_ID");
        defaults.logThreadName = readBoolean("kolobok.debuglog.logThreadName", "KLB_DEBUGLOG_LOG_THREAD_NAME");
        defaults.logHttpRequest = readBoolean("kolobok.debuglog.logHttpRequest", "KLB_DEBUGLOG_LOG_HTTP_REQUEST");
        defaults.tag = readString("kolobok.debuglog.tag", "KLB_DEBUGLOG_TAG");
        defaults.slowThresholdMs = readLong("kolobok.debuglog.slowThresholdMs", "KLB_DEBUGLOG_SLOW_THRESHOLD_MS");
        defaults.logLocals = readBoolean("kolobok.debuglog.logLocals", "KLB_DEBUGLOG_LOG_LOCALS");
        defaults.logLocalsOnException = readBoolean("kolobok.debuglog.logLocalsOnException",
                "KLB_DEBUGLOG_LOG_LOCALS_ON_EXCEPTION");
        return defaults;
    }

    public DebugLogDefaults merge(DebugLogDefaults overrides) {
        if (overrides == null) {
            return this;
        }
        DebugLogDefaults merged = new DebugLogDefaults();
        merged.lineHeatMap = overrides.lineHeatMap != null ? overrides.lineHeatMap : lineHeatMap;
        merged.lineHeatMapOnException = overrides.lineHeatMapOnException != null ? overrides.lineHeatMapOnException : lineHeatMapOnException;
        merged.subHeatMap = overrides.subHeatMap != null ? overrides.subHeatMap : subHeatMap;
        merged.logDuration = overrides.logDuration != null ? overrides.logDuration : logDuration;
        merged.aggregateChildren = overrides.aggregateChildren != null ? overrides.aggregateChildren : aggregateChildren;
        merged.logArgs = overrides.logArgs != null ? overrides.logArgs : logArgs;
        merged.mask = overrides.mask != null ? overrides.mask : mask;
        merged.maxArgLength = overrides.maxArgLength != null ? overrides.maxArgLength : maxArgLength;
        merged.resultMask = overrides.resultMask != null ? overrides.resultMask : resultMask;
        merged.maxResultLength = overrides.maxResultLength != null ? overrides.maxResultLength : maxResultLength;
        merged.logLevel = overrides.logLevel != null ? overrides.logLevel : logLevel;
        merged.logFormat = overrides.logFormat != null ? overrides.logFormat : logFormat;
        merged.logThreadId = overrides.logThreadId != null ? overrides.logThreadId : logThreadId;
        merged.logThreadName = overrides.logThreadName != null ? overrides.logThreadName : logThreadName;
        merged.logHttpRequest = overrides.logHttpRequest != null ? overrides.logHttpRequest : logHttpRequest;
        merged.tag = overrides.tag != null ? overrides.tag : tag;
        merged.slowThresholdMs = overrides.slowThresholdMs != null ? overrides.slowThresholdMs : slowThresholdMs;
        merged.logLocals = overrides.logLocals != null ? overrides.logLocals : logLocals;
        merged.logLocalsOnException = overrides.logLocalsOnException != null ? overrides.logLocalsOnException : logLocalsOnException;
        return merged;
    }

    public Boolean getLineHeatMap() {
        return lineHeatMap;
    }

    public void setLineHeatMap(Boolean lineHeatMap) {
        this.lineHeatMap = lineHeatMap;
    }

    public Boolean getLineHeatMapOnException() {
        return lineHeatMapOnException;
    }

    public void setLineHeatMapOnException(Boolean lineHeatMapOnException) {
        this.lineHeatMapOnException = lineHeatMapOnException;
    }

    public Boolean getSubHeatMap() {
        return subHeatMap;
    }

    public void setSubHeatMap(Boolean subHeatMap) {
        this.subHeatMap = subHeatMap;
    }

    public Boolean getLogDuration() {
        return logDuration;
    }

    public void setLogDuration(Boolean logDuration) {
        this.logDuration = logDuration;
    }

    public Boolean getAggregateChildren() {
        return aggregateChildren;
    }

    public void setAggregateChildren(Boolean aggregateChildren) {
        this.aggregateChildren = aggregateChildren;
    }

    public Boolean getLogArgs() {
        return logArgs;
    }

    public void setLogArgs(Boolean logArgs) {
        this.logArgs = logArgs;
    }

    public String getMask() {
        return mask;
    }

    public void setMask(String mask) {
        this.mask = mask;
    }

    public Integer getMaxArgLength() {
        return maxArgLength;
    }

    public void setMaxArgLength(Integer maxArgLength) {
        this.maxArgLength = maxArgLength;
    }

    public String getResultMask() {
        return resultMask;
    }

    public void setResultMask(String resultMask) {
        this.resultMask = resultMask;
    }

    public Integer getMaxResultLength() {
        return maxResultLength;
    }

    public void setMaxResultLength(Integer maxResultLength) {
        this.maxResultLength = maxResultLength;
    }

    public DebugLog.LogLevel getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(DebugLog.LogLevel logLevel) {
        this.logLevel = logLevel;
    }

    public DebugLog.LogFormat getLogFormat() {
        return logFormat;
    }

    public void setLogFormat(DebugLog.LogFormat logFormat) {
        this.logFormat = logFormat;
    }

    public Boolean getLogThreadId() {
        return logThreadId;
    }

    public void setLogThreadId(Boolean logThreadId) {
        this.logThreadId = logThreadId;
    }

    public Boolean getLogThreadName() {
        return logThreadName;
    }

    public void setLogThreadName(Boolean logThreadName) {
        this.logThreadName = logThreadName;
    }

    public Boolean getLogHttpRequest() {
        return logHttpRequest;
    }

    public void setLogHttpRequest(Boolean logHttpRequest) {
        this.logHttpRequest = logHttpRequest;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public Long getSlowThresholdMs() {
        return slowThresholdMs;
    }

    public void setSlowThresholdMs(Long slowThresholdMs) {
        this.slowThresholdMs = slowThresholdMs;
    }

    public Boolean getLogLocals() {
        return logLocals;
    }

    public void setLogLocals(Boolean logLocals) {
        this.logLocals = logLocals;
    }

    public Boolean getLogLocalsOnException() {
        return logLocalsOnException;
    }

    public void setLogLocalsOnException(Boolean logLocalsOnException) {
        this.logLocalsOnException = logLocalsOnException;
    }

    private static Boolean readBoolean(String propKey, String envKey) {
        String value = readString(propKey, envKey);
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Integer readInt(String propKey, String envKey) {
        String value = readString(propKey, envKey);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long readLong(String propKey, String envKey) {
        String value = readString(propKey, envKey);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String readString(String propKey, String envKey) {
        String value = System.getProperty(propKey);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        value = System.getenv(envKey);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return null;
    }

    private static <T extends Enum<T>> T readEnum(Class<T> type, String propKey, String envKey) {
        String value = readString(propKey, envKey);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
