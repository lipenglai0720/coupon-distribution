package cn.quanshu;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.exception.ExcelAnalysisException;
import com.alibaba.excel.read.listener.ReadListener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

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
      var listener =
          new ReadListener<Map<Integer, String>>() {
            @Override
            public void invoke(Map<Integer, String> cells, AnalysisContext context) {
              long row = context.readRowHolder().getRowIndex() + 1L;
              if (row > 1_000_001) throw new BusinessException("文件最多一百万条用户记录");
              if (row > checkpoint) consumer.accept(parse(row, cells.get(0)));
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {}
          };
      try {
        EasyExcel.read(path.toFile(), listener)
            .headRowNumber(0)
            .ignoreEmptyRow(false)
            .sheet(0)
            .doRead();
      } catch (ExcelAnalysisException e) {
        if (e.getCause() instanceof RuntimeException cause) throw cause;
        throw e;
      }
    }
  }
}
