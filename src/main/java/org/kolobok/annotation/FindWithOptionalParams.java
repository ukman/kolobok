package org.kolobok.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for marking methods in Spring repositories interfaces which should to find entities by optional params.
 * @author Sergey Grigorchuk sergey.grigorchuk@gmail.com
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.SOURCE)
public @interface FindWithOptionalParams {
}
