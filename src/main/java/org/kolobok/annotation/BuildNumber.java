package org.kolobok.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for marking Number/String fields that should contain information about build number.
 * @author Sergey Grigorchuk sergey.grigorchuk@gmail.com
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface BuildNumber {

    /**
     * Url which provide incremented build number.
     * See https://backendless.com/docs/rest/ut_increment_by_1__return_current.html as example.
     * @return URL
     */
    String url();

    /**
     * Method that should be used to get build number via HTTP protocol.
     * @return
     */
    String method() default "POST";

    /**
     * Timeout
     * @return timeout in ms
     */
    int timeout() default 10000;

    /**
     * Default value that should be used as build number if something goes wrong. If value is empty (or missed)
     * and something goes wrong then compilation will be failed with message.
     * @return default value as string
     */
    String defaultValue() default "";
}
