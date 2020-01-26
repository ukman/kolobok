package org.kolobok.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for marking long/Long fields that should contain information about compilation time.
 * @author Sergey Grigorchuk sergey.grigorchuk@gmail.com
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface CompileTime {
}
