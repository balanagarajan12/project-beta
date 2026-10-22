package com.etp.migration.transformation;

import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.TransformationException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * CONCAT expression {@code street_name + ' - ' + city}.
 * Quoted text is copied as written, including spaces. {@code +} joins the pieces.
 */
final class ConcatExpression {

	private ConcatExpression() {
	}

	static List<String> fieldNames(String expression) {
		if (expression == null || expression.isBlank()) {
			return List.of();
		}
		try {
			List<String> names = new ArrayList<>();
			for (Piece piece : parse(expression, "expression")) {
				if (piece.field != null) {
					names.add(piece.field);
				}
			}
			return List.copyOf(names);
		} catch (TransformationException ex) {
			return List.of();
		}
	}

	static String render(String expression, Function<String, Object> values, String targetField) {
		StringBuilder result = new StringBuilder();
		for (Piece piece : parse(expression, targetField)) {
			if (piece.field != null) {
				Object value = values.apply(piece.field);
				if (value != null) {
					result.append(value);
				}
			} else {
				result.append(piece.literal);
			}
		}
		return result.toString();
	}

	private static List<Piece> parse(String expression, String targetField) {
		List<Piece> pieces = new ArrayList<>();
		int index = 0;
		boolean expectValue = true;
		while (index < expression.length()) {
			char current = expression.charAt(index);
			if (Character.isWhitespace(current)) {
				index++;
				continue;
			}
			if (expectValue) {
				if (current == '\'' || current == '"') {
					Quoted quoted = quoted(expression, index, targetField);
					pieces.add(Piece.literal(quoted.text));
					index = quoted.next;
				} else if (isNameStart(current)) {
					int start = index;
					index++;
					while (index < expression.length() && isNamePart(expression.charAt(index))) {
						index++;
					}
					pieces.add(Piece.field(expression.substring(start, index)));
				} else {
					throw invalid(targetField);
				}
				expectValue = false;
				continue;
			}
			if (current != '+') {
				throw invalid(targetField);
			}
			expectValue = true;
			index++;
		}
		if (pieces.isEmpty() || expectValue) {
			throw invalid(targetField);
		}
		return List.copyOf(pieces);
	}

	private static Quoted quoted(String expression, int open, String targetField) {
		char quote = expression.charAt(open);
		StringBuilder text = new StringBuilder();
		int index = open + 1;
		while (index < expression.length()) {
			char current = expression.charAt(index);
			if (current == '\\' && index + 1 < expression.length()) {
				text.append(expression.charAt(index + 1));
				index += 2;
				continue;
			}
			if (current == quote) {
				return new Quoted(text.toString(), index + 1);
			}
			text.append(current);
			index++;
		}
		throw invalid(targetField);
	}

	private static boolean isNameStart(char current) {
		return Character.isLetter(current) || current == '_';
	}

	private static boolean isNamePart(char current) {
		return Character.isLetterOrDigit(current) || current == '_';
	}

	private static TransformationException invalid(String targetField) {
		return new TransformationException(
				MigrationErrorCodes.TRANSFORMATION_ERROR,
				targetField,
				"CONCAT expression is invalid");
	}

	private record Piece(String field, String literal) {
		private static Piece field(String name) {
			return new Piece(name, null);
		}

		private static Piece literal(String text) {
			return new Piece(null, text);
		}
	}

	private record Quoted(String text, int next) {
	}
}
