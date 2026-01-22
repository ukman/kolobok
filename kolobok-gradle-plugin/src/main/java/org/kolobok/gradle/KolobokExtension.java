package org.kolobok.gradle;

import org.kolobok.annotation.DebugLog;
import org.kolobok.transformer.DebugLogDefaults;

import java.util.Locale;

public class KolobokExtension {
    private final DebugLogDefaultsConfig debugLogDefaults = new DebugLogDefaultsConfig();

    public DebugLogDefaultsConfig getDebugLogDefaults() {
        return debugLogDefaults;
    }

    public static class DebugLogDefaultsConfig {
        private Boolean lineHeatMap;
        private Boolean lineHeatMapOnException;
        private Boolean subHeatMap;
        private Boolean logDuration;
        private Boolean aggregateChildren;
        private Boolean logArgs;
        private String mask;
        private Integer maxArgLength;
        private String logLevel;
        private String logFormat;
        private Boolean logThreadId;
        private Boolean logThreadName;
        private Boolean logLocals;
        private Boolean logLocalsOnException;

        public DebugLogDefaults toDefaults() {
            DebugLogDefaults defaults = new DebugLogDefaults();
            defaults.setLineHeatMap(lineHeatMap);
            defaults.setLineHeatMapOnException(lineHeatMapOnException);
            defaults.setSubHeatMap(subHeatMap);
            defaults.setLogDuration(logDuration);
            defaults.setAggregateChildren(aggregateChildren);
            defaults.setLogArgs(logArgs);
            defaults.setMask(mask);
            defaults.setMaxArgLength(maxArgLength);
            if (logLevel != null) {
                defaults.setLogLevel(parseEnum(DebugLog.LogLevel.class, logLevel));
            }
            if (logFormat != null) {
                defaults.setLogFormat(parseEnum(DebugLog.LogFormat.class, logFormat));
            }
            defaults.setLogThreadId(logThreadId);
            defaults.setLogThreadName(logThreadName);
            defaults.setLogLocals(logLocals);
            defaults.setLogLocalsOnException(logLocalsOnException);
            return defaults;
        }

        private <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
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

        public String getLogLevel() {
            return logLevel;
        }

        public void setLogLevel(String logLevel) {
            this.logLevel = logLevel;
        }

        public String getLogFormat() {
            return logFormat;
        }

        public void setLogFormat(String logFormat) {
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
    }
}
