-- Optional development seed for ETP Core UI Phase 1.
-- Login works without this seed; authorization/check requires a matching principals row.
-- After creating a Keycloak user, set principals.external_id to the user's JWT sub (Keycloak subject).

INSERT INTO applications (
    application_id, application_code, application_name, description, status, created_at, updated_at
) VALUES (
    'a1000001-0000-4000-8000-000000000001',
    'etp-core-ui',
    'Identity & Access Management',
    'ETP Core UI platform console',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (application_code) DO NOTHING;

INSERT INTO resources (
    resource_id, application_id, resource_name, resource_type, status, created_at, updated_at
) VALUES (
    'a1000002-0000-4000-8000-000000000002',
    'a1000001-0000-4000-8000-000000000001',
    'IAM Administration',
    'API',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (application_id, resource_type, resource_name) DO NOTHING;

INSERT INTO permissions (
    permission_id, resource_id, permission_code, permission_name, action, status, created_at, updated_at
) VALUES (
    'a1000003-0000-4000-8000-000000000003',
    'a1000002-0000-4000-8000-000000000002',
    'CREATE_IAM',
    'Create IAM',
    'CREATE',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (resource_id, permission_code) DO NOTHING;

INSERT INTO roles (
    role_id, role_name, description, role_type, status, created_at, updated_at
) VALUES (
    'a1000004-0000-4000-8000-000000000004',
    'platform-admin',
    'ETP platform administrator',
    'GENERAL',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (role_id) DO NOTHING;

INSERT INTO groups (
    group_id, group_name, description, group_type, status, created_at, updated_at
) VALUES (
    'a1000005-0000-4000-8000-000000000005',
    'platform-admins',
    'ETP platform administrators',
    'HUMAN',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (group_id) DO NOTHING;

INSERT INTO role_application_permissions (
    mapping_id, role_id, application_id, permission_id, created_at
) VALUES (
    'a1000006-0000-4000-8000-000000000006',
    'a1000004-0000-4000-8000-000000000004',
    'a1000001-0000-4000-8000-000000000001',
    'a1000003-0000-4000-8000-000000000003',
    CURRENT_TIMESTAMP
) ON CONFLICT (role_id, application_id, permission_id) DO NOTHING;

INSERT INTO group_role_mappings (
    mapping_id, group_id, role_id, created_at
) VALUES (
    'a1000007-0000-4000-8000-000000000007',
    'a1000005-0000-4000-8000-000000000005',
    'a1000004-0000-4000-8000-000000000004',
    CURRENT_TIMESTAMP
) ON CONFLICT (group_id, role_id) DO NOTHING;

-- Principal: update external_id to Keycloak user sub before using /authorization/check.
INSERT INTO principals (
    principal_id, principal_type, issuer, external_id, principal_name, status, created_at, updated_at
) VALUES (
    'a1000008-0000-4000-8000-000000000008',
    'USER',
    'http://localhost:8180/realms/etp',
    'REPLACE_WITH_KEYCLOAK_SUB',
    'platform-admin',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (issuer, external_id) DO NOTHING;

INSERT INTO principal_group_mappings (
    mapping_id, principal_id, group_id, created_at
) VALUES (
    'a1000009-0000-4000-8000-000000000009',
    'a1000008-0000-4000-8000-000000000008',
    'a1000005-0000-4000-8000-000000000005',
    CURRENT_TIMESTAMP
) ON CONFLICT (principal_id, group_id) DO NOTHING;
