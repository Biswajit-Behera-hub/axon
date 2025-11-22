package com.axon.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.List;

/**
 * Represents a high-level educational unit that groups related lessons together.
 * <p>
 * This record serves as a container for a specific topic (identified by {@code moduleName})
 * and its associated list of {@link Lesson} objects. It is designed for JSON data binding
 * and ensures safe access to the lesson list by automatically handling null inputs.
 * </p>
 *
 * @param moduleName The unique identifier or display title of the learning module.
 * @param lessons    The collection of individual {@link Lesson} objects contained in this module.
 *                   Guaranteed to be non-null after initialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LearningModule(String moduleName, List<Lesson> lessons) {

    /**
     * Compact constructor that performs defensive initialization.
     * <p>
     * This constructor ensures that the {@code lessons} list is never {@code null}.
     * If a {@code null} value is provided during instantiation (e.g., via JSON deserialization
     * where the field is missing), it is replaced with an empty, immutable list to prevent
     * {@link NullPointerException} during runtime usage.
     * </p>
     */
    public LearningModule {
        if (lessons == null) {
            lessons = Collections.emptyList();
        }
    }
}