package com.etp.migration.mapping;

/**
 * Target-table foreign key for a parent/child relationship.
 * {@code column} is the child table column. {@code referencesColumn} is the parent table column
 * it references. That column is not assumed to be named {@code id}.
 */
public record TargetForeignKey(String column, String referencesColumn) {
}
