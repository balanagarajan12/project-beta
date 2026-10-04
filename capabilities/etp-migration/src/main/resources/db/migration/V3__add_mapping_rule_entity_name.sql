-- Associates a mapping rule with a configured logical entity.
-- NULL keeps existing flat mappings unchanged.

ALTER TABLE migration_mapping_rule
    ADD COLUMN entity_name VARCHAR(100);
