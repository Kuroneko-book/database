package customdb.minidb.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class Page {
    public static final int PAGE_SIZE = 4096;
    public static final int SLOT_SIZE = 64;
    public static final int MAX_SLOTS = PAGE_SIZE / SLOT_SIZE;
    public static final int MAX_VALUE_LENGTH = 55;

    private final byte[] data;

    public Page() {
        this(new byte[PAGE_SIZE]);
    }

    public Page(byte[] data) {
        if (data == null || data.length != PAGE_SIZE) {
            throw new IllegalArgumentException(
                "Page data must be exactly " + PAGE_SIZE + " bytes.");
        }
        this.data = data;
    }

    public boolean isSlotUsed(int slot) {
        checkSlotRange(slot);
        return data[slot * SLOT_SIZE] == 1;
    }

    public int getKey(int slot) {
        checkSlotRange(slot);
        int offset = slot * SLOT_SIZE + 1;
        return ByteBuffer.wrap(data, offset, 4).getInt();
    }

    public Record getRecord(int slot) {
        if (!isSlotUsed(slot)) {
            return null;
        }
        int offset = slot * SLOT_SIZE;
        ByteBuffer bb = ByteBuffer.wrap(data, offset, SLOT_SIZE);
        bb.get(); // フラグスキップ
        int key = bb.getInt();
        int valLen = bb.getInt();
        byte[] valBytes = new byte[valLen];
        bb.get(valBytes);
        String value = new String(valBytes, StandardCharsets.UTF_8);
        return new Record(key, value);
    }

    public void writeRecord(int slot, int key, String value) {
        checkSlotRange(slot);
        int offset = slot * SLOT_SIZE;
        ByteBuffer bb = ByteBuffer.wrap(data, offset, SLOT_SIZE);
        bb.put((byte) 1);
        bb.putInt(key);

        byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(valBytes.length, MAX_VALUE_LENGTH);
        bb.putInt(len);
        bb.put(valBytes, 0, len);
    }

    public void updateValue(int slot, String value) {
        checkSlotRange(slot);
        int offset = slot * SLOT_SIZE;
        ByteBuffer bb = ByteBuffer.wrap(data, offset, SLOT_SIZE);
        bb.get();
        bb.getInt(); // キーをスキップ

        byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(valBytes.length, MAX_VALUE_LENGTH);
        bb.putInt(len);
        bb.put(valBytes, 0, len);
    }

    public void deleteRecord(int slot) {
        checkSlotRange(slot);
        data[slot * SLOT_SIZE] = 0;
    }

    public byte[] getData() {
        return data;
    }

    private void checkSlotRange(int slot) {
        if (slot < 0 || slot >= MAX_SLOTS) {
            throw new IndexOutOfBoundsException("Slot index out of range: " + slot);
        }
    }
}
