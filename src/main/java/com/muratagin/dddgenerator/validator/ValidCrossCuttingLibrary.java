package com.muratagin.dddgenerator.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Constraint(validatedBy = CrossCuttingLibraryValidator.class)
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCrossCuttingLibrary {
    String message() default "If any cross-cutting library field is provided, all of groupId, name, version, and dependencies are required, and dependencies must include 'domain', 'application', and 'persistence'.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
} 