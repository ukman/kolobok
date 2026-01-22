package org.kolobok.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface DebugLog {
    boolean lineHeatMap() default false;
    boolean lineHeatMapOnException() default false;
    boolean subHeatMap() default false;
    boolean logDuration() default false;
    boolean aggregateChildren() default true;
    boolean logArgs() default true;
    String mask() default "";
    int maxArgLength() default 200;
    LogLevel logLevel() default LogLevel.DEBUG;
    LogFormat logFormat() default LogFormat.HUMAN;
    boolean logThreadId() default false;
    boolean logThreadName() default false;
    boolean logLocals() default false;
    boolean logLocalsOnException() default false;

    enum LogLevel {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    enum LogFormat {
        HUMAN,
        JSON
    }
}
