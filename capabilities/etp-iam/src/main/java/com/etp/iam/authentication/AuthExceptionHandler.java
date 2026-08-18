package com.etp.iam.authentication;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AuthController.class)
public class AuthExceptionHandler {

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

	@ExceptionHandler(InvalidGrantTypeException.class)
	public ResponseEntity<Map<String, Object>> handleInvalidGrantType(InvalidGrantTypeException ex) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", "unsupported_grant_type");
		body.put("message", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
	}

	@ExceptionHandler(KeycloakAuthenticationException.class)
	public ResponseEntity<Map<String, Object>> handleKeycloakAuthentication(KeycloakAuthenticationException ex) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", ex.getError() != null ? ex.getError() : "invalid_client");
		body.put("message", ex.getMessage());
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
	}

	@ExceptionHandler(KeycloakTokenException.class)
	public ResponseEntity<Map<String, Object>> handleKeycloakTokenException(KeycloakTokenException ex) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", "token_service_unavailable");
		body.put("message", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
	}
}
