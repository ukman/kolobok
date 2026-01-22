package org.kolobok.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
public @interface DebugLogIgnore {
    Mode mode() default Mode.ALWAYS;

    enum Mode {
        ALWAYS,
        SUCCESS
    }
}
