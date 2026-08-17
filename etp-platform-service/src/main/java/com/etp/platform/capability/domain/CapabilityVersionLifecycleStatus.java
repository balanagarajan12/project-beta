package com.etp.platform.capability.domain;

public enum CapabilityVersionLifecycleStatus {
    DRAFT,
    VALIDATED,
    ACTIVE,
    DEPRECATED,
    RETIRED,
    CANCELLED;

    public boolean canTransitionTo(CapabilityVersionLifecycleStatus target) {
        if (this == target) {
            return true;
        }
        return switch (this) {
            case DRAFT -> target == VALIDATED || target == CANCELLED;
            case VALIDATED -> target == ACTIVE || target == CANCELLED;
            case ACTIVE -> target == DEPRECATED;
            case DEPRECATED -> target == RETIRED;
            case RETIRED, CANCELLED -> false;
        };
    }

    public boolean isTerminal() {
        return this == RETIRED || this == CANCELLED;
    }

    public boolean isImmutableContent() {
        return this == ACTIVE || this == DEPRECATED || this == RETIRED;
    }
}
