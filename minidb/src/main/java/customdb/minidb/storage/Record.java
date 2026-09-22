package customdb.minidb.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public record Record(String key, byte[] value) {
  // メモリ上の Record オブジェクトを、ディスクに保存可能なバイト配列（byte[]）に変換する
  public byte[] serialize() {
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    byte[] valBytes = value;

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
    return new Record(key, valBytes);
  }

  // 指定したレコードをシリアライズした際の総バイト数を返す
  public int getSerializedSize() {
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    byte[] valBytes = value;
    return 2 + keyBytes.length + 2 + valBytes.length;
  }
}
