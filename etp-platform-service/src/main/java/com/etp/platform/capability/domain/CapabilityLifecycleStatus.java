package com.etp.platform.capability.domain;

public enum CapabilityLifecycleStatus {
    DRAFT,
    ACTIVE,
    DEPRECATED,
    RETIRED;

    public boolean canTransitionTo(CapabilityLifecycleStatus target) {
        if (this == target) {
            return true;
        }
        return switch (this) {
            case DRAFT -> target == ACTIVE;
            case ACTIVE -> target == DEPRECATED;
            case DEPRECATED -> target == RETIRED;
            case RETIRED -> false;
        };
    }
}
