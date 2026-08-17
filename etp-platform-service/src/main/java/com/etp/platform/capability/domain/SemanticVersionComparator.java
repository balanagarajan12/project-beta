package com.etp.platform.capability.domain;

import com.etp.platform.common.exception.BusinessException;

public final class SemanticVersionComparator {

    private SemanticVersionComparator() {
    }

    public static int compare(String left, String right) {
        VersionParts leftParts = parse(left);
        VersionParts rightParts = parse(right);
        int majorCompare = Integer.compare(leftParts.major, rightParts.major);
        if (majorCompare != 0) {
            return majorCompare;
        }
        int minorCompare = Integer.compare(leftParts.minor, rightParts.minor);
        if (minorCompare != 0) {
            return minorCompare;
        }
        return Integer.compare(leftParts.patch, rightParts.patch);
    }

    private static VersionParts parse(String version) {
        String coreVersion = version.split("-")[0].split("\\+")[0];
        String[] segments = coreVersion.split("\\.");
        if (segments.length != 3) {
            throw new BusinessException("INVALID_VERSION", "Invalid semantic version: " + version);
        }
        try {
            return new VersionParts(
                    Integer.parseInt(segments[0]),
                    Integer.parseInt(segments[1]),
                    Integer.parseInt(segments[2]));
        } catch (NumberFormatException ex) {
            throw new BusinessException("INVALID_VERSION", "Invalid semantic version: " + version);
        }
    }

    private record VersionParts(int major, int minor, int patch) {
    }
}
