package com.etp.migration.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class EntityLoadOrderTest {

	@Test
	void parentsLoadBeforeChildrenAndIndependentEntitiesAreOrderedByName() {
		List<String> order = EntityLoadOrder.parentsFirst(
				List.of("Site", "GeographicSubAddress", "GeographicAddress"),
				List.of(new EntityLoadOrder.Dependency("GeographicAddress", "GeographicSubAddress")));

		assertThat(order).containsExactly("GeographicAddress", "GeographicSubAddress", "Site");
		assertThat(order.indexOf("GeographicAddress")).isLessThan(order.indexOf("GeographicSubAddress"));
	}

	@Test
	void duplicateEdgesDoNotChangeTheOrder() {
		List<String> order = EntityLoadOrder.parentsFirst(
				List.of("Child", "Parent"),
				List.of(
						new EntityLoadOrder.Dependency("Parent", "Child"),
						new EntityLoadOrder.Dependency("Parent", "Child")));

		assertThat(order).containsExactly("Parent", "Child");
	}

	@Test
	void cyclesAreReported() {
		assertThatThrownBy(() -> EntityLoadOrder.parentsFirst(
				List.of("Account", "Customer"),
				List.of(
						new EntityLoadOrder.Dependency("Customer", "Account"),
						new EntityLoadOrder.Dependency("Account", "Customer"))))
				.isInstanceOf(InvalidEntityRelationshipConfigurationException.class)
				.hasMessage("Entity relationship graph contains a cycle: Account -> Customer -> Account");
	}
}
