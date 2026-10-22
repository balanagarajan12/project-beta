package com.etp.migration.repository;

import com.etp.migration.entity.MigrationIdentityAllocation;
import java.util.List;

public interface MigrationIdentityAllocationBatch {

	void insertAll(List<MigrationIdentityAllocation> rows);
}
