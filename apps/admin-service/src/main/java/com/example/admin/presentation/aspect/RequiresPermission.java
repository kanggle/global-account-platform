package com.example.admin.presentation.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission key(s) required to invoke a controller method.
 *
 * <p>Either {@link #value()} (single) or {@link #allOf()} (every key required)
 * may be used; specifying both sets makes the effective requirement the union.
 * The evaluation occurs AFTER Spring Security's {@code @PreAuthorize} — both
 * checks must pass.
 *
 * <p>See specs/services/admin-service/rbac.md Permission Evaluation Algorithm.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    String value() default "";
    String[] allOf() default {};
}
