package com.ipas.assistant.service.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 向量二进制编解码的单元测试。
 *
 * <p>这组断言守的是"存进去什么、读出来就是什么"。这类编解码最容易犯两个错：
 * <b>字节序写反</b>（读出来是乱码数值）与<b>长度校验缺失</b>（脏数据直接下标越界）。
 * 两者在编译期都发现不了，且不一定每次都触发，只有断言能兜住。
 */
class VectorsTest {

    @Test
    @DisplayName("往返一致：编码后再解码应还原出完全相同的向量")
    void roundTripRestoresExactValues() {
        float[] original = {0.1f, -0.25f, 1.0f, 0.0f, 3.14159f, -1000.5f};

        float[] restored = Vectors.fromBytes(Vectors.toBytes(original));

        assertArrayEquals(original, restored, "往返后必须逐元素一致");
    }

    @Test
    @DisplayName("尺寸：1024 维向量编码后恰好 4096 字节（比 JSON 的约 18KB 小得多）")
    void encodedSizeIsFourBytesPerDimension() {
        float[] vec = new float[1024];

        assertEquals(1024 * 4, Vectors.toBytes(vec).length);
    }

    @Test
    @DisplayName("字节序固定小端：1.0f（0x3F800000）应排成 00 00 80 3F")
    void bytesAreLittleEndian() {
        byte[] bytes = Vectors.toBytes(new float[]{1.0f});

        // 1.0f 的 IEEE-754 表示是 0x3F800000；小端顺序即"低位在前"：
        // 00 00 80 3F。这里逐字节断言，比"转成大端整数再比"更直白、也不容易算反。
        assertEquals(0x00, bytes[0] & 0xFF, "第 1 字节（最低位）");
        assertEquals(0x00, bytes[1] & 0xFF, "第 2 字节");
        assertEquals(0x80, bytes[2] & 0xFF, "第 3 字节");
        assertEquals(0x3F, bytes[3] & 0xFF, "第 4 字节（最高位）");
    }

    @Test
    @DisplayName("脏数据防御：长度不是 4 的倍数时返回 null，而不是越界崩溃")
    void malformedLengthReturnsNull() {
        assertNull(Vectors.fromBytes(new byte[]{1, 2, 3}), "3 字节不是 4 的倍数");
        assertNull(Vectors.fromBytes(new byte[0]), "空数组");
        assertNull(Vectors.fromBytes(null), "null");
    }

    @Test
    @DisplayName("编码 null 返回 null（供调用方判断“这条没有向量”）")
    void encodeNullReturnsNull() {
        assertNull(Vectors.toBytes(null));
    }
}
