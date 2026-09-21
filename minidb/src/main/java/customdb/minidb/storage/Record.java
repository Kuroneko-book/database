package customdb.minidb.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public record Record(String key, String value) {
  // メモリ上の Record オブジェクトを、ディスクに保存可能なバイト配列（byte[]）に変換する
  // <p>フォーマット: [キー長(2B)] + [キー(UTF-8)] + [値長(2B)] + [値(UTF-8)]
  public byte[] serialize() {
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);

    byte[] bytes = new byte[2 + keyBytes.length + 2 + valBytes.length];
    ByteBuffer buffer = ByteBuffer.wrap(bytes);

    buffer.putShort((short) keyBytes.length);
    buffer.put(keyBytes);
    buffer.putShort((short) valBytes.length);
    buffer.put(valBytes);

    return bytes;
  }

  public static Record deserialize(ByteBuffer buffer) {
    int keyLen = Short.toUnsignedInt(buffer.getShort());
    byte[] keyBytes = new byte[keyLen];
    buffer.get(keyBytes);
    String key = new String(keyBytes, StandardCharsets.UTF_8);

    int valLen = Short.toUnsignedInt(buffer.getShort());
    byte[] valBytes = new byte[valLen];
    buffer.get(valBytes);
    String value = new String(valBytes, StandardCharsets.UTF_8);

    return new Record(key, value);
  }

  // 指定したレコードをシリアライズした際の総バイト数を返す
  public int getSerializedSize() {
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);
    return 2 + keyBytes.length + 2 + valBytes.length;
  }
}
