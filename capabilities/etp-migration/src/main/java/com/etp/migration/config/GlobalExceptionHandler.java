package com.etp.migration.config;

import com.etp.migration.batch.MigrationBatchNotFoundException;
import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.target.InvalidPromotionConfigurationException;
import com.etp.migration.mapping.InvalidEntityRelationshipConfigurationException;
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
import com.etp.migration.migration.RequestValidationException;
import com.etp.migration.migration.dto.ApiErrorResponse;
import com.etp.migration.migration.dto.ApiFieldError;
import com.etp.migration.promotion.PromotionException;
import com.etp.migration.run.DefinitionNotActiveException;
import com.etp.migration.run.InvalidStateTransitionException;
import com.etp.migration.run.MigrationRunNotFoundException;
import com.etp.migration.promotion.PromotionNotFoundException;
import com.etp.migration.reconciliation.ReconciliationNotFoundException;
import com.etp.migration.reconciliation.ReconciliationNotReadyException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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
		List<ApiFieldError> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> new ApiFieldError(
						error.getField(),
						simpleValue(error.getRejectedValue()),
						error.getDefaultMessage(),
						null))
				.toList();
		return validationFailed(errors, request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleUnreadable(
			HttpMessageNotReadableException ex,
			HttpServletRequest request) {
		InvalidFormatException invalid = invalidFormat(ex);
		if (invalid != null && invalid.getTargetType() != null && invalid.getTargetType().isEnum()) {
			String field = fieldPath(invalid);
			return validationFailed(List.of(new ApiFieldError(
					field,
					simpleValue(invalid.getValue()),
					unsupportedMessage(field),
					enumNames(invalid.getTargetType()))), request);
		}
		JacksonException mapping = jacksonFailure(ex);
		String field = mapping == null ? null : fieldPath(mapping);
		return validationFailed(List.of(new ApiFieldError(
				field,
				null,
				"Request body is invalid or uses an unsupported value",
				null)), request);
	}

	@ExceptionHandler(RequestValidationException.class)
	public ResponseEntity<ApiErrorResponse> handleRequestValidation(
			RequestValidationException ex,
			HttpServletRequest request) {
		return validationFailed(ex.errors(), request);
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
			InvalidEntityRelationshipConfigurationException.class,
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

	private ResponseEntity<ApiErrorResponse> validationFailed(List<ApiFieldError> errors, HttpServletRequest request) {
		ApiErrorResponse body = new ApiErrorResponse(
				Instant.now(),
				HttpStatus.BAD_REQUEST.value(),
				"Validation Failed",
				"Request validation failed",
				request.getRequestURI(),
				null,
				errors);
		return ResponseEntity.badRequest().body(body);
	}

	private static InvalidFormatException invalidFormat(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof InvalidFormatException invalid) {
				return invalid;
			}
			current = current.getCause();
		}
		return null;
	}

	private static JacksonException jacksonFailure(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof JacksonException mapping) {
				return mapping;
			}
			current = current.getCause();
		}
		return null;
	}

	private static String fieldPath(JacksonException failure) {
		StringBuilder path = new StringBuilder();
		for (JacksonException.Reference reference : failure.getPath()) {
			if (reference.getIndex() >= 0) {
				path.append('[').append(reference.getIndex()).append(']');
			}
			else if (reference.getPropertyName() != null) {
				if (!path.isEmpty()) {
					path.append('.');
				}
				path.append(reference.getPropertyName());
			}
		}
		return path.isEmpty() ? null : path.toString();
	}

	private static String unsupportedMessage(String field) {
		if (field != null && (field.equals("transformationType") || field.endsWith(".transformationType"))) {
			return "Unsupported transformation type";
		}
		if (field != null && (field.equals("loadMode") || field.endsWith(".loadMode"))) {
			return "Unsupported load mode";
		}
		return "Unsupported value";
	}

	private static List<String> enumNames(Class<?> type) {
		return Arrays.stream(type.getEnumConstants()).map(Object::toString).toList();
	}

	private static String simpleValue(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
			return String.valueOf(value);
		}
		return null;
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
