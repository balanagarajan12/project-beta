package com.etp.migration.connector.target.uam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UamParentExistenceTest {

	@Test
	void sameBatchParentIdentityIsNotLookedUpAgain() {
		assertThat(UamParentExistence.unresolved(Set.of("LOC-1"), List.of("LOC-1"))).isEmpty();
	}

	@Test
	void allocatedIdentityWithoutAPlannedParentRowStaysUnresolved() {
		assertThat(UamParentExistence.unresolved(Set.of(), List.of("LOC-9")))
				.containsExactly("LOC-9");
	}
}
