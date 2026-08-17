package com.etp.platform.capability.domain;

import com.etp.platform.common.exception.BusinessException;

public final class LifecycleTransitionValidator {

    private LifecycleTransitionValidator() {
    }

    public static void validateCapabilityTransition(String currentStatus, String targetStatus) {
        CapabilityLifecycleStatus current = parseCapabilityStatus(currentStatus);
        CapabilityLifecycleStatus target = parseCapabilityStatus(targetStatus);
        if (!current.canTransitionTo(target)) {
            throw BusinessException.conflict(
                    "INVALID_LIFECYCLE_TRANSITION",
                    "Capability lifecycle transition from " + current + " to " + target + " is not allowed");
        }
    }

    public static void validateVersionTransition(String currentStatus, String targetStatus) {
        CapabilityVersionLifecycleStatus current = parseVersionStatus(currentStatus);
        CapabilityVersionLifecycleStatus target = parseVersionStatus(targetStatus);
        if (!current.canTransitionTo(target)) {
            throw BusinessException.conflict(
                    "INVALID_LIFECYCLE_TRANSITION",
                    "Capability version lifecycle transition from " + current + " to " + target + " is not allowed");
        }
    }

    private static CapabilityLifecycleStatus parseCapabilityStatus(String status) {
        try {
            return CapabilityLifecycleStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("INVALID_LIFECYCLE_STATUS", "Unknown capability lifecycle status: " + status);
        }
    }

    private static CapabilityVersionLifecycleStatus parseVersionStatus(String status) {
        try {
            return CapabilityVersionLifecycleStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("INVALID_LIFECYCLE_STATUS", "Unknown capability version lifecycle status: " + status);
        }
    }
}
