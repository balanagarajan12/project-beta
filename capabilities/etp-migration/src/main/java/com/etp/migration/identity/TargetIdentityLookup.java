package com.etp.migration.identity;

import java.util.List;
import java.util.Map;

/**
 * Bulk source-to-target lookup for one configured entity.
 * Callers pass a bounded list. The map contains only identities that were found.
 * A missing key is unresolved. The source identity must not be copied into the map as a stand-in target id.
 * {@code targetEntity} is not a physical table name. This interface does not insert or update target rows.
 */
public interface TargetIdentityLookup {

	Map<String, String> lookup(String entityName, List<String> sourceIdentities);
}
