-- Local UAM tables required by UamPostgreSqlTargetConnector.
-- Columns and defaults match the connector integration test, which follows the
-- columns that load writes. This script does not replace the real UAM schema.

CREATE SCHEMA IF NOT EXISTS uam;

CREATE TABLE IF NOT EXISTS uam.location (
    id varchar(64) PRIMARY KEY,
    href varchar(255),
    name varchar(255),
    description text,
    status varchar(32) DEFAULT 'PLANNED',
    version varchar(16) DEFAULT '1.0',
    at_type varchar(64) NOT NULL,
    at_base_type varchar(64),
    at_schema_location varchar(255),
    valid_for_start_date timestamptz,
    valid_for_end_date timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz
);

CREATE TABLE IF NOT EXISTS uam.geographic_location (
    id varchar(64) PRIMARY KEY,
    location_id varchar(64) NOT NULL UNIQUE REFERENCES uam.location (id),
    spatial_ref_sys varchar(128) NOT NULL DEFAULT 'urn:ogc:def:crs:EPSG::4326',
    accuracy varchar(64),
    geo_json_type varchar(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS uam.geographic_address (
    id varchar(64) PRIMARY KEY,
    location_id varchar(64) NOT NULL UNIQUE REFERENCES uam.location (id),
    geographic_location_id varchar(64) REFERENCES uam.geographic_location (id),
    validation_status varchar(32) NOT NULL DEFAULT 'DRAFT',
    address_type varchar(64) DEFAULT 'CivicAddress',
    street_number varchar(32),
    street_number_last varchar(32),
    street_number_suffix varchar(16),
    street_name varchar(128),
    street_type varchar(32),
    street_suffix varchar(32),
    postcode varchar(32),
    postcode_extension varchar(16),
    locality varchar(128),
    city varchar(128),
    state_or_province varchar(128),
    country varchar(64),
    country_code varchar(8)
);

CREATE TABLE IF NOT EXISTS uam.location_geometry (
    id varchar(64) PRIMARY KEY,
    location_id varchar(64) NOT NULL UNIQUE REFERENCES uam.geographic_location (id),
    geometry_type varchar(32) NOT NULL,
    x numeric,
    y numeric,
    z numeric,
    raw_geojson jsonb
);

CREATE TABLE IF NOT EXISTS uam.geographic_sub_address (
    id varchar(64) PRIMARY KEY,
    address_id varchar(64) NOT NULL REFERENCES uam.geographic_address (id),
    geographic_location_id varchar(64) REFERENCES uam.geographic_location (id),
    private_street_name varchar(128) NOT NULL,
    private_street_number varchar(32) NOT NULL,
    sub_unit_type varchar(64),
    sub_unit_number varchar(32),
    sub_unit_name varchar(128),
    level_type varchar(64),
    level_number varchar(32),
    building_name varchar(128)
);
