package com.etp.migration.config;

import com.etp.migration.batch.MigrationBatchNotFoundException;
import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.target.InvalidPromotionConfigurationException;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.source.SourceInspectionException;
import com.etp.migration.connector.source.UnsupportedFileFormatException;
import com.etp.migration.connector.source.UnsupportedSourceTypeException;
import com.etp.migration.connector.storage.ArtifactNotFoundException;
import com.etp.migration.connector.storage.InvalidArtifactPathException;
import com.etp.migration.connector.storage.UnsupportedStorageProviderException;
import com.etp.migration.migration.DuplicateMigrationDefinitionException;
import com.etp.migration.migration.DuplicateMigrationMappingException;
import com.etp.migration.migration.DuplicateMappingRuleException;
import com.etp.migration.migration.InvalidMappingReferenceException;
import com.etp.migration.migration.MappingNotFoundException;
import com.etp.migration.migration.MigrationDefinitionNotFoundException;
import com.etp.migration.migration.MigrationSourceNotFoundException;
import com.etp.migration.migration.dto.ApiErrorResponse;
import com.etp.migration.promotion.PromotionException;
import com.etp.migration.run.DefinitionNotActiveException;
import com.etp.migration.run.InvalidStateTransitionException;
import com.etp.migration.run.MigrationRunNotFoundException;
import com.etp.migration.promotion.PromotionNotFoundException;
import com.etp.migration.reconciliation.ReconciliationNotFoundException;
import com.etp.migration.reconciliation.ReconciliationNotReadyException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleValidation(
			MethodArgumentNotValidException ex,
			HttpServletRequest request) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + ": " + error.getDefaultMessage())
				.collect(Collectors.joining("; "));
		return error(HttpStatus.BAD_REQUEST, message, request, null);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleUnreadable(
			HttpMessageNotReadableException ex,
			HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, "Request body is invalid or uses an unsupported value", request);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
			IllegalArgumentException ex,
			HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
	}

	@ExceptionHandler(InvalidMappingReferenceException.class)
	public ResponseEntity<ApiErrorResponse> handleInvalidMappingReference(
			InvalidMappingReferenceException ex,
			HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
	}

	@ExceptionHandler({
			MigrationDefinitionNotFoundException.class,
			MappingNotFoundException.class,
			MigrationSourceNotFoundException.class,
			MigrationRunNotFoundException.class,
			MigrationBatchNotFoundException.class,
			ArtifactNotFoundException.class,
			ReconciliationNotFoundException.class,
			PromotionNotFoundException.class
	})
	public ResponseEntity<ApiErrorResponse> handleNotFound(RuntimeException ex, HttpServletRequest request) {
		return error(HttpStatus.NOT_FOUND, ex.getMessage(), request);
	}

	@ExceptionHandler({
			InvalidSourceConfigurationException.class,
			InvalidPromotionConfigurationException.class,
			UnsupportedSourceTypeException.class,
			UnsupportedFileFormatException.class,
			UnsupportedStorageProviderException.class,
			InvalidArtifactPathException.class
	})
	public ResponseEntity<ApiErrorResponse> handleBadSource(RuntimeException ex, HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
	}

	@ExceptionHandler(SourceInspectionException.class)
	public ResponseEntity<ApiErrorResponse> handleInspectionFailure(
			SourceInspectionException ex,
			HttpServletRequest request) {
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "Source inspection failed", request);
	}

	@ExceptionHandler(TargetInfrastructureException.class)
	public ResponseEntity<ApiErrorResponse> handleTargetInfrastructure(
			TargetInfrastructureException ex,
			HttpServletRequest request) {
		return error(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), request, ex.errorCode());
	}

	@ExceptionHandler(PromotionException.class)
	public ResponseEntity<ApiErrorResponse> handlePromotion(
			PromotionException ex,
			HttpServletRequest request) {
		HttpStatus status = HttpStatus.resolve(ex.status());
		if (status == null) {
			status = HttpStatus.BAD_REQUEST;
		}
		return error(status, ex.getMessage(), request, ex.errorCode());
	}

	@ExceptionHandler({
			DuplicateMigrationDefinitionException.class,
			DuplicateMigrationMappingException.class,
			DuplicateMappingRuleException.class,
			DefinitionNotActiveException.class,
			InvalidStateTransitionException.class,
			ReconciliationNotReadyException.class
	})
	public ResponseEntity<ApiErrorResponse> handleConflict(RuntimeException ex, HttpServletRequest request) {
		String code = ex instanceof ReconciliationNotReadyException ready ? ready.errorCode() : null;
		return error(HttpStatus.CONFLICT, ex.getMessage(), request, code);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiErrorResponse> handleDataIntegrity(
			DataIntegrityViolationException ex,
			HttpServletRequest request) {
		return error(HttpStatus.CONFLICT, "The request conflicts with an existing resource", request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
	}

	private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String message, HttpServletRequest request) {
		return error(status, message, request, null);
	}

	private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String message, HttpServletRequest request, String code) {
		ApiErrorResponse body = new ApiErrorResponse(
				Instant.now(),
				status.value(),
				status.getReasonPhrase(),
				message,
				request.getRequestURI(),
				code);
		return ResponseEntity.status(status).body(body);
	}
}
