package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.batch.BatchPlanner;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.migration.MigrationDefinitionNotFoundException;
import com.etp.migration.migration.dto.PagedResponse;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.promotion.PromotionException;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.dto.MigrationRunResponse;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class MigrationRunServiceTest {

	@Mock
	private MigrationDefinitionRepository migrationDefinitionRepository;

	@Mock
	private MigrationRunRepository migrationRunRepository;

	@Mock
	private MigrationBatchRepository migrationBatchRepository;

	@Mock
	private MigrationPromotionRepository promotionRepository;

	@Mock
	private MigrationErrorRepository migrationErrorRepository;

	@Mock
	private BatchPlanner batchPlanner;

	private MigrationRunService migrationRunService;

	@BeforeEach
	void setUp() {
		migrationRunService = new MigrationRunService(
				migrationDefinitionRepository,
				migrationRunRepository,
				migrationBatchRepository,
				promotionRepository,
				migrationErrorRepository,
				batchPlanner);
	}

	@Test
	void retryIsRejectedWithoutTouchingBatchesWhenPromotionIsInProgressOrCompleted() {
		UUID runId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		run.setStatus(MigrationRunStatus.COMPLETED_WITH_ERRORS);
		when(migrationRunRepository.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
		when(promotionRepository.existsByMigrationRun_IdAndStatusIn(
				runId, EnumSet.of(PromotionStatus.PROMOTING, PromotionStatus.PROMOTED))).thenReturn(true);

		assertThatThrownBy(() -> migrationRunService.retry(runId))
				.isInstanceOfSatisfying(PromotionException.class, ex -> {
					assertThat(ex.status()).isEqualTo(409);
					assertThat(ex.errorCode()).isEqualTo(MigrationErrorCodes.RETRY_BLOCKED_BY_PROMOTION);
				});
		verify(migrationBatchRepository, never()).findByMigrationRun_IdAndStatusIn(any(), any());
		assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.COMPLETED_WITH_ERRORS);
	}

	@Test
	void listAllReturnsAnEmptyPage() {
		when(migrationRunRepository.findAllByRecency(any()))
				.thenReturn(Page.empty(PageRequest.of(0, 20)));

		PagedResponse<MigrationRunResponse> page = migrationRunService.listAll(0, 20, null);

		assertThat(page.content()).isEmpty();
		assertThat(page.page()).isZero();
		assertThat(page.size()).isEqualTo(20);
		assertThat(page.totalElements()).isZero();
		assertThat(page.totalPages()).isZero();
	}

	@Test
	void listAllUsesTheSamePageLimitsAsMigrationDefinitions() {
		when(migrationRunRepository.findAllByRecency(any()))
				.thenReturn(Page.empty(PageRequest.of(0, 100)));

		migrationRunService.listAll(-3, 500, null);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(migrationRunRepository).findAllByRecency(pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).isZero();
		assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
		assertThat(pageable.getValue().getSort().isSorted()).isFalse();
		verify(migrationRunRepository, never()).findByDefinitionRecency(any(), any());
	}

	@Test
	void listAllFiltersByDefinitionAndRejectsAnUnknownDefinition() {
		UUID definitionId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		when(migrationDefinitionRepository.existsById(definitionId)).thenReturn(true);
		when(migrationRunRepository.findByDefinitionRecency(eq(definitionId), any()))
				.thenReturn(Page.empty(PageRequest.of(0, 20)));

		PagedResponse<MigrationRunResponse> page = migrationRunService.listAll(0, 20, definitionId);

		assertThat(page.content()).isEmpty();
		verify(migrationRunRepository, never()).findAllByRecency(any());

		UUID missing = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		when(migrationDefinitionRepository.existsById(missing)).thenReturn(false);
		assertThatThrownBy(() -> migrationRunService.listAll(0, 20, missing))
				.isInstanceOf(MigrationDefinitionNotFoundException.class);
	}
}
