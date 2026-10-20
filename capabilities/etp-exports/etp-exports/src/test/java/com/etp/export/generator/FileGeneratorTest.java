package com.etp.export.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import com.etp.export.dto.TabularData;

class FileGeneratorTest {

    private static TabularData sample() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("name", "Smith, \"J\"");
        row.put("formula", "=SUM(A1)");
        row.put("amount", -12.5);
        return new TabularData(List.of("ID", "Name", "Formula", "Amount"),
                List.of("id", "name", "formula", "amount"), List.of(row));
    }

    @Test
    void csvEscapesAndGuardsFormulas() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CsvFileGenerator(false).generate(sample(), null, out);

        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv).isEqualTo("ID,Name,Formula,Amount\r\n1,\"Smith, \"\"J\"\"\",'=SUM(A1),-12.5\r\n");
    }

    @Test
    void xlsxHasHeaderAndTypedCells() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new XlsxFileGenerator().generate(sample(), "Orders", out);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheet("Orders");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Name");
            assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(sheet.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(-12.5);
        }
    }
}
