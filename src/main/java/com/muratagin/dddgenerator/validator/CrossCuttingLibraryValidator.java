package com.muratagin.dddgenerator.validator;

import com.muratagin.dddgenerator.dto.CrossCuttingLibraryRequest;
import com.muratagin.dddgenerator.dto.ProjectRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CrossCuttingLibraryValidator implements ConstraintValidator<ValidCrossCuttingLibrary, ProjectRequest> {

    @Override
    public void initialize(ValidCrossCuttingLibrary constraintAnnotation) {
    }

    @Override
    public boolean isValid(ProjectRequest projectRequest, ConstraintValidatorContext context) {
        if (projectRequest == null || projectRequest.getCrossCuttingLibrary() == null) {
            return true; // No cross-cutting library provided, so it's valid by default.
        }

        CrossCuttingLibraryRequest cclRequest = projectRequest.getCrossCuttingLibrary();

        // If no part of the cross-cutting library is populated, it's considered valid (optional feature).
        if (!cclRequest.isPopulated()) {
            return true;
        }

        // If at least one field is populated, then all essential fields must be populated.
        if (!cclRequest.isFullyPopulated()) {
            // It's better to set the message on the specific field if possible, or class level
            // context.disableDefaultConstraintViolation();
            // context.buildConstraintViolationWithTemplate("If any cross-cutting library detail is provided, all of groupId, name, version, and dependencies are required.")
            // .addPropertyNode("crossCuttingLibrary").addConstraintViolation();
            return false; // Default message from annotation will be used.
        }

        // If fully populated, check the content of dependencies.
        List<String> requiredDeps = Arrays.asList("domain", "application", "persistence");
        Set<String> providedDeps = new HashSet<>(cclRequest.getDependencies());

        if (!providedDeps.containsAll(requiredDeps)) {
            // context.disableDefaultConstraintViolation();
            // context.buildConstraintViolationWithTemplate("Cross-cutting library dependencies must include 'domain', 'application', and 'persistence'.")
            // .addPropertyNode("crossCuttingLibrary.dependencies").addConstraintViolation();
            return false; // Default message from annotation will be used, or a more specific one can be set.
        }

        return true;
    }
} 