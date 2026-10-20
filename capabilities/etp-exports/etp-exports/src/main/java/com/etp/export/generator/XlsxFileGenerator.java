package com.etp.export.generator;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import com.etp.export.dto.FileType;
import com.etp.export.dto.TabularData;

/**
 * Streaming XLSX writer (SXSSF keeps only a window of rows in memory).
 * Rolls over to a new sheet ("Data_2", ...) when Excel's row limit is reached.
 */
public class XlsxFileGenerator implements FileGenerator {

    private static final int ROW_WINDOW = 200;
    private static final int MAX_DATA_ROWS_PER_SHEET = SpreadsheetVersion.EXCEL2007.getMaxRows() - 1; // minus header
    private static final int MAX_CELL_CHARS = SpreadsheetVersion.EXCEL2007.getMaxTextLength();
    private static final int WIDTH_SAMPLE_ROWS = 200;
    private static final long MAX_EXACT_DOUBLE = 1L << 53;

    @Override
    public FileType fileType() {
        return FileType.XLSX;
    }

    @Override
    @SuppressWarnings("deprecation") // dispose() still needed on some POI 5.x versions to delete temp files
    public void generate(TabularData data, String sheetName, OutputStream out) throws IOException {
        SXSSFWorkbook wb = new SXSSFWorkbook(ROW_WINDOW);
        wb.setCompressTempFiles(true);
        try (wb) {
            CellStyle headerStyle = headerStyle(wb);
            String baseName = WorkbookUtil.createSafeSheetName(
                    sheetName == null || sheetName.isBlank() ? "Data" : sheetName);
            int[] widths = columnWidths(data);

            List<String> keys = data.keys();
            List<Map<String, Object>> rows = data.rows();
            int sheetNo = 1;
            Sheet sheet = newSheet(wb, baseName, sheetNo, data.headers(), headerStyle, widths);
            int rowIdx = 1;

            for (Map<String, Object> record : rows) {
                if (rowIdx > MAX_DATA_ROWS_PER_SHEET) {
                    sheet = newSheet(wb, baseName, ++sheetNo, data.headers(), headerStyle, widths);
                    rowIdx = 1;
                }
                Row row = sheet.createRow(rowIdx++);
                for (int c = 0; c < keys.size(); c++) {
                    setCell(row.createCell(c), record.get(keys.get(c)));
                }
            }
            wb.write(out);
            out.flush();
        } finally {
            wb.dispose();
        }
    }

    private static Sheet newSheet(SXSSFWorkbook wb, String baseName, int no, List<String> headers,
                                  CellStyle style, int[] widths) {
        String name = no == 1 ? baseName : WorkbookUtil.createSafeSheetName(
                (baseName.length() > 26 ? baseName.substring(0, 26) : baseName) + "_" + no);
        Sheet sheet = wb.createSheet(name);
        Row header = sheet.createRow(0);
        for (int c = 0; c < headers.size(); c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(headers.get(c));
            cell.setCellStyle(style);
            sheet.setColumnWidth(c, widths[c]);
        }
        sheet.createFreezePane(0, 1);
        if (!headers.isEmpty()) {
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, headers.size() - 1));
        }
        return sheet;
    }

    private static void setCell(Cell cell, Object v) {
        switch (v) {
            case null -> cell.setBlank();
            case Boolean b -> cell.setCellValue(b);
            case Integer i -> cell.setCellValue(i);
            case Long l when Math.abs(l) <= MAX_EXACT_DOUBLE -> cell.setCellValue(l);
            case Double d when !d.isNaN() && !d.isInfinite() -> cell.setCellValue(d);
            case Float f -> cell.setCellValue(f.doubleValue());
            case Short s -> cell.setCellValue(s);
            case BigInteger bi when bi.bitLength() <= 53 -> cell.setCellValue(bi.doubleValue());
            case BigDecimal bd when bd.precision() <= 15 -> cell.setCellValue(bd.doubleValue());
            // large ids / high-precision amounts stay as text so no digits are lost
            default -> cell.setCellValue(truncate(CellValues.asText(v)));
        }
    }

    private static String truncate(String s) {
        return s.length() > MAX_CELL_CHARS ? s.substring(0, MAX_CELL_CHARS) : s;
    }

    private static CellStyle headerStyle(SXSSFWorkbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** Estimates column widths from header and the first rows (autoSize is slow with SXSSF). */
    private static int[] columnWidths(TabularData data) {
        List<String> keys = data.keys();
        int[] chars = new int[keys.size()];
        for (int c = 0; c < keys.size(); c++) {
            chars[c] = data.headers().get(c) == null ? 8 : data.headers().get(c).length() + 2;
        }
        int sample = Math.min(WIDTH_SAMPLE_ROWS, data.rows().size());
        for (int r = 0; r < sample; r++) {
            Map<String, Object> row = data.rows().get(r);
            for (int c = 0; c < keys.size(); c++) {
                chars[c] = Math.max(chars[c], CellValues.asText(row.get(keys.get(c))).length() + 1);
            }
        }
        int[] widths = new int[chars.length];
        for (int c = 0; c < chars.length; c++) {
            widths[c] = Math.min(Math.max(chars[c], 8), 60) * 256;
        }
        return widths;
    }
}
