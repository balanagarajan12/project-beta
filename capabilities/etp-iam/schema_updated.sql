-- ============================================================
-- ETP IAM Platform - Database Schema (Finalized)
-- PostgreSQL 17
--
-- Runtime schema is applied via Flyway:
--   src/main/resources/db/migration/V1__iam_schema.sql
-- This file is kept as a human-readable reference copy.
-- ============================================================
-- 1. Applications are PLATFORM-LEVEL and are NOT tenant-owned.
-- 2. Tenants belong to Companies.
-- 3. Resources and Permissions belong to platform Applications.
-- 4. Tenant/scope access is assigned to Groups via group_tenant_assignments.
-- 5. Authentication is handled by Keycloak; principals represent USER and SERVICE identities.
-- 6. Legacy users, user_group_mappings, and access_assignments are NOT used.
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- TABLE 1: companies
-- ============================================================
CREATE TABLE companies (
    company_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id VARCHAR(100) UNIQUE,
    company_code VARCHAR(50) NOT NULL UNIQUE,
    company_name VARCHAR(200) NOT NULL,
    legal_name VARCHAR(200),
    trading_name VARCHAR(200),
    description TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- ============================================================
-- TABLE 2: tenants
-- ============================================================
CREATE TABLE tenants (
    tenant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(company_id),
    external_id VARCHAR(100) UNIQUE,
    tenant_code VARCHAR(50) NOT NULL,
    tenant_name VARCHAR(200) NOT NULL,
    description TEXT,
    environment VARCHAR(30),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),

    CONSTRAINT uq_tenant_company_code UNIQUE (company_id, tenant_code)
);

CREATE INDEX idx_tenants_company_id ON tenants(company_id);

-- ============================================================
-- TABLE 3: applications
-- ============================================================
CREATE TABLE applications (
    application_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id VARCHAR(100) UNIQUE,
    application_code VARCHAR(50) NOT NULL UNIQUE,
    application_name VARCHAR(200) NOT NULL,
    description TEXT,
    application_type VARCHAR(50),
    version VARCHAR(30),
    owner VARCHAR(100),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- ============================================================
-- TABLE 4: resources
-- ============================================================
CREATE TABLE resources (
    resource_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(application_id),
    external_id VARCHAR(100) UNIQUE,
    resource_name VARCHAR(200) NOT NULL,
    description TEXT,
    resource_type VARCHAR(30) NOT NULL,
    resource_path VARCHAR(500),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),

    CONSTRAINT uq_resource_application_type_name
        UNIQUE (application_id, resource_type, resource_name),
    CONSTRAINT chk_resource_type
        CHECK (resource_type IN ('API', 'FEATURE'))
);

CREATE INDEX idx_resources_application_id ON resources(application_id);
CREATE INDEX idx_resources_resource_type ON resources(resource_type);

-- ============================================================
-- TABLE 5: permissions
-- ============================================================
CREATE TABLE permissions (
    permission_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_id UUID NOT NULL REFERENCES resources(resource_id),
    external_id VARCHAR(100) UNIQUE,
    permission_code VARCHAR(100) NOT NULL,
    permission_name VARCHAR(200) NOT NULL,
    description TEXT,
    action VARCHAR(50),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),

    CONSTRAINT uq_permission_resource_code UNIQUE (resource_id, permission_code)
);

CREATE INDEX idx_permissions_resource_id ON permissions(resource_id);
CREATE INDEX idx_permissions_permission_code ON permissions(permission_code);

-- ============================================================
-- TABLE 6: principals
-- ============================================================
CREATE TABLE principals (
    principal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_type VARCHAR(30) NOT NULL,
    issuer VARCHAR(500) NOT NULL,
    external_id VARCHAR(200) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),

    CONSTRAINT uq_principal_issuer_external_id UNIQUE (issuer, external_id),
    CONSTRAINT chk_principal_type
        CHECK (principal_type IN ('USER', 'SERVICE'))
);

CREATE INDEX idx_principals_principal_type ON principals(principal_type);
CREATE INDEX idx_principals_principal_name ON principals(principal_name);

-- ============================================================
-- TABLE 7: groups
-- ============================================================
CREATE TABLE groups (
    group_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id VARCHAR(100) UNIQUE,
    group_name VARCHAR(200) NOT NULL,
    description TEXT,
    group_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),

    CONSTRAINT chk_group_type
        CHECK (group_type IN ('HUMAN', 'SERVICE', 'MIXED'))
);

CREATE INDEX idx_groups_group_type ON groups(group_type);
CREATE INDEX idx_groups_group_name ON groups(group_name);

-- ============================================================
-- TABLE 8: principal_group_mappings
-- ============================================================
CREATE TABLE principal_group_mappings (
    mapping_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_id UUID NOT NULL REFERENCES principals(principal_id) ON DELETE CASCADE,
    group_id UUID NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),

    CONSTRAINT uq_principal_group UNIQUE (principal_id, group_id)
);

CREATE INDEX idx_principal_group_principal_id ON principal_group_mappings(principal_id);
CREATE INDEX idx_principal_group_group_id ON principal_group_mappings(group_id);

-- ============================================================
-- TABLE 9: roles
-- ============================================================
CREATE TABLE roles (
    role_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id VARCHAR(100) UNIQUE,
    role_name VARCHAR(200) NOT NULL,
    description TEXT,
    role_type VARCHAR(30),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),

    CONSTRAINT chk_role_type
        CHECK (role_type IS NULL OR role_type IN ('API', 'UI', 'GENERAL'))
);

CREATE INDEX idx_roles_role_name ON roles(role_name);

-- ============================================================
-- TABLE 10: group_role_mappings
-- ============================================================
CREATE TABLE group_role_mappings (
    mapping_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(role_id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),

    CONSTRAINT uq_group_role UNIQUE (group_id, role_id)
);

CREATE INDEX idx_group_role_group_id ON group_role_mappings(group_id);
CREATE INDEX idx_group_role_role_id ON group_role_mappings(role_id);

-- ============================================================
-- TABLE 11: role_application_permissions
-- ============================================================
CREATE TABLE role_application_permissions (
    mapping_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id UUID NOT NULL REFERENCES roles(role_id) ON DELETE CASCADE,
    application_id UUID NOT NULL REFERENCES applications(application_id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(permission_id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),

    CONSTRAINT uq_role_application_permission
        UNIQUE (role_id, application_id, permission_id)
);

CREATE INDEX idx_rap_role_id ON role_application_permissions(role_id);
CREATE INDEX idx_rap_application_id ON role_application_permissions(application_id);
CREATE INDEX idx_rap_permission_id ON role_application_permissions(permission_id);

-- ============================================================
-- TABLE 12: scopes
-- ============================================================
CREATE TABLE scopes (
    scope_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id VARCHAR(100) UNIQUE,
    scope_name VARCHAR(200) NOT NULL,
    scope_type VARCHAR(50) NOT NULL,
    description TEXT,
    scope_value VARCHAR(500) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_scopes_scope_type ON scopes(scope_type);
CREATE INDEX idx_scopes_scope_value ON scopes(scope_value);

-- ============================================================
-- TABLE 13: group_tenant_assignments
-- ============================================================
CREATE TABLE group_tenant_assignments (
    assignment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    scope_id UUID REFERENCES scopes(scope_id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    start_date TIMESTAMP,
    end_date TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),

    CONSTRAINT uq_group_tenant_scope UNIQUE (group_id, tenant_id, scope_id)
);

CREATE INDEX idx_group_tenant_assignments_group_id ON group_tenant_assignments(group_id);
CREATE INDEX idx_group_tenant_assignments_tenant_id ON group_tenant_assignments(tenant_id);
CREATE INDEX idx_group_tenant_assignments_scope_id ON group_tenant_assignments(scope_id);

-- ============================================================
-- Updated-at trigger function
-- ============================================================
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_companies_updated_at
    BEFORE UPDATE ON companies
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tenants_updated_at
    BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_applications_updated_at
    BEFORE UPDATE ON applications
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_resources_updated_at
    BEFORE UPDATE ON resources
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_permissions_updated_at
    BEFORE UPDATE ON permissions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_principals_updated_at
    BEFORE UPDATE ON principals
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_groups_updated_at
    BEFORE UPDATE ON groups
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_roles_updated_at
    BEFORE UPDATE ON roles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_scopes_updated_at
    BEFORE UPDATE ON scopes
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_group_tenant_assignments_updated_at
    BEFORE UPDATE ON group_tenant_assignments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
