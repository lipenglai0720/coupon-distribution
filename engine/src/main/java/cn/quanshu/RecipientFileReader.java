package cn.quanshu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.apache.poi.openxml4j.opc.*;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.*;
import org.apache.poi.xssf.model.*;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;

/**
 * Reads the first column of the first sheet; reports source row numbers for durable checkpoints.
 */
public final class RecipientFileReader {
  public record Row(long number, Long user, String error) {}

  private RecipientFileReader() {}

  private static Row parse(long row, String raw) {
    String value = raw == null ? "" : raw.replace("\uFEFF", "").trim();
    if (value.isEmpty() || (row == 1 && Set.of("user_id", "用户编号", "用户ID").contains(value)))
      return new Row(row, null, null);
    try {
      long user = Long.parseLong(value);
      if (user < 1) throw new NumberFormatException();
      return new Row(row, user, null);
    } catch (NumberFormatException e) {
      return new Row(row, null, "第一列必须是正整数用户编号，请将长编号保存为文本");
    }
  }

  public static void read(Path path, long checkpoint, Consumer<Row> consumer) throws Exception {
    if (path.toString().endsWith(".csv")) {
      try (var in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
        String line;
        long row = 0;
        while ((line = in.readLine()) != null) {
          row++;
          if (row > 1000001) throw new BusinessException("文件最多一百万条用户记录");
          if (row > checkpoint) consumer.accept(parse(row, line));
        }
      }
    } else {
      try (var pkg = OPCPackage.open(path.toFile(), PackageAccess.READ)) {
        var reader = new XSSFReader(pkg);
        var shared = new ReadOnlySharedStringsTable(pkg);
        var sheets = reader.getSheetsData();
        if (!sheets.hasNext()) throw new BusinessException("工作簿没有工作表");
        var handler =
            new XSSFSheetXMLHandler(
                reader.getStylesTable(),
                null,
                shared,
                new XSSFSheetXMLHandler.SheetContentsHandler() {
                  private String first;

                  @Override
                  public void startRow(int row) {
                    first = null;
                  }

                  @Override
                  public void endRow(int row) {
                    if (row > 1000000) throw new BusinessException("文件最多一百万条用户记录");
                    if (row + 1L > checkpoint) consumer.accept(parse(row + 1L, first));
                  }

                  @Override
                  public void cell(String reference, String value, XSSFComment comment) {
                    if (reference != null && reference.matches("A[0-9]+")) first = value;
                  }

                  @Override
                  public void headerFooter(String text, boolean header, String tagName) {}
                },
                new DataFormatter(Locale.ROOT),
                false);
        var parser = XMLHelper.newXMLReader();
        parser.setContentHandler(handler);
        try (var sheet = sheets.next()) {
          parser.parse(new InputSource(sheet));
        }
      }
    }
  }
}
