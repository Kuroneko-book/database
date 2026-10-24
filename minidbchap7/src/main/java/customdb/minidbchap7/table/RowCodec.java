package customdb.minidbchap7.table;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

// Rowをスキーマ定義に従ってバイナリへ変換する
public final class RowCodec {
  private RowCodec() {}

  public static byte[] encode(Schema schema, Row row) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      for (Schema.Column column : schema.getColumns()) {
        Object value = row.get(column.name());

        switch (column.type()) {
          case INTEGER -> out.writeInt((Integer) value);
          case FLOAT -> out.writeFloat((Float) value);
          case DOUBLE -> out.writeDouble((Double) value);
          case STRING -> {
            byte[] utf8 = ((String) value).getBytes(StandardCharsets.UTF_8);
            validateVarcharLength(column, utf8);
            out.writeInt(utf8.length);
            out.write(utf8);
          }
        }
      }
    }

    return bytes.toByteArray();
  }

  public static Row decode(Schema schema, byte[] data) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      Row row = new Row();

      for (Schema.Column column : schema.getColumns()) {
        switch (column.type()) {
          case INTEGER -> row.put(column.name(), in.readInt());
          case FLOAT -> row.put(column.name(), in.readFloat());
          case DOUBLE -> row.put(column.name(), in.readDouble());
          case STRING -> row.put(column.name(), readString(in, column));
        }
      }

      return row;
    } catch (EOFException e) {
      throw new IllegalArgumentException("Row data is too short for schema.", e);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to decode row.", e);
    }
  }

  private static String readString(DataInputStream in, Schema.Column column) throws IOException {
    int length = in.readInt();

    if (length < 0) {
      throw new IllegalArgumentException("Corrupt row data: negative string length.");
    }
    if (column.length() > 0 && length > column.length()) {
      throw new IllegalArgumentException(
          "Corrupt row data: string length "
              + length
              + " exceeds limit "
              + column.length()
              + " for column '"
              + column.name()
              + "'.");
    }

    byte[] utf8 = new byte[length];
    in.readFully(utf8);
    return new String(utf8, StandardCharsets.UTF_8);
  }

  private static void validateVarcharLength(Schema.Column column, byte[] utf8) {
    if (column.length() > 0 && utf8.length > column.length()) {
      throw new IllegalArgumentException(
          "Value is too long for column '"
              + column.name()
              + "' (UTF-8 length "
              + utf8.length
              + " exceeds limit "
              + column.length()
              + ").");
    }
  }
}
