package com.etp.migration.performance;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streams a deterministic three-entity CSV dataset. Entity names are EntityA, EntityB, and EntityC.
 * Files are written at benchmark time and are not stored in git.
 */
public final class EntityDatasetGenerator {

	public static final String HEADER =
			"entity_a_id,entity_a_label,entity_b_id,entity_b_label,entity_b_parent,entity_c_id,entity_c_label,entity_c_parent";

	private EntityDatasetGenerator() {
	}

	public record Shape(int roots, int middles, int leaves) {
		public Shape {
			if (roots < 1 || middles < 1 || leaves < 1) {
				throw new IllegalArgumentException("dataset shape requires at least one of each entity");
			}
		}

		public int entities() {
			return roots + middles + leaves;
		}

		public long relationships() {
			return (long) middles + leaves;
		}

		public static Shape forLeaves(int leaves) {
			int roots = Math.max(1, leaves / 1000);
			int middles = Math.max(roots, leaves / 100);
			return new Shape(roots, middles, leaves);
		}
	}

	public static Shape writeSingle(Path file, int leaves) {
		Shape shape = Shape.forLeaves(leaves);
		write(file, shape, RowKind.ALL);
		return shape;
	}

	public static Shape writeMulti(Path directory, int leaves) {
		Shape shape = Shape.forLeaves(leaves);
		try {
			Files.createDirectories(directory);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		write(directory.resolve("entity_a.csv"), shape, RowKind.ROOT);
		write(directory.resolve("entity_b.csv"), shape, RowKind.MIDDLE);
		write(directory.resolve("entity_c.csv"), shape, RowKind.LEAF);
		return shape;
	}

	private static void write(Path file, Shape shape, RowKind kind) {
		try {
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		try (BufferedWriter writer = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), 1 << 20)) {
			writer.write(HEADER);
			writer.write('\n');
			if (kind == RowKind.ALL || kind == RowKind.ROOT) {
				for (int index = 1; index <= shape.roots(); index++) {
					writer.write(rootRow(index));
					writer.write('\n');
				}
			}
			if (kind == RowKind.ALL || kind == RowKind.MIDDLE) {
				for (int index = 1; index <= shape.middles(); index++) {
					writer.write(middleRow(shape, index));
					writer.write('\n');
				}
			}
			if (kind == RowKind.ALL || kind == RowKind.LEAF) {
				for (int index = 1; index <= shape.leaves(); index++) {
					writer.write(leafRow(shape, index));
					writer.write('\n');
				}
			}
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static String rootId(int index) {
		return "A" + index;
	}

	static String middleId(int index) {
		return "B" + index;
	}

	static String leafId(int index) {
		return "C" + index;
	}

	private static String rootRow(int index) {
		return rootId(index) + ",Root " + index + ",,,,,,";
	}

	private static String middleRow(Shape shape, int index) {
		int parent = ((index - 1) % shape.roots()) + 1;
		return ",," + middleId(index) + ",Middle " + index + "," + rootId(parent) + ",,,";
	}

	private static String leafRow(Shape shape, int index) {
		int parent = ((index - 1) % shape.middles()) + 1;
		return ",,,,," + leafId(index) + ",Leaf " + index + "," + middleId(parent);
	}

	private enum RowKind {
		ALL,
		ROOT,
		MIDDLE,
		LEAF
	}
}
