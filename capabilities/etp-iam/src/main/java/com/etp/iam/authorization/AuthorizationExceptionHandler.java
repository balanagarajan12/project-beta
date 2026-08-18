package com.etp.iam.authorization;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AuthorizationController.class)
public class AuthorizationExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException ex) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
			fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", "invalid_request");
		body.put("message", "Request validation failed");
		body.put("details", fieldErrors);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
	}

	@ExceptionHandler(AuthorizationException.class)
	public ResponseEntity<Map<String, Object>> handleAuthorizationException(AuthorizationException ex) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", ex.getReason().name().toLowerCase());
		body.put("message", ex.getMessage());

		HttpStatus status = switch (ex.getReason()) {
			case INVALID_IDENTITY, PRINCIPAL_NOT_FOUND -> HttpStatus.UNAUTHORIZED;
			case PRINCIPAL_INACTIVE -> HttpStatus.FORBIDDEN;
		};

		return ResponseEntity.status(status).body(body);
	}
}
