package com.ipas.assistant.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 向量与字节之间的编解码工具。
 *
 * <h2>为什么要存二进制而不是 JSON 文本</h2>
 *
 * <p>一条 1024 维向量，用 JSON 文本存约 <b>18KB</b>（每个 float 平均十几个字符 + 逗号），
 * 用 float32 二进制存只要 <b>4KB</b> —— 体积差 4.5 倍。
 *
 * <p>更关键的是<b>读取时的开销</b>：检索要把该用户的全部片段向量读进内存算相似度，
 * 用 JSON 存就得对每一行做一次文本解析（几千行就是几千次解析，且每次都要新建大量临时对象）。
 * 二进制版本是一次内存拷贝（{@code ByteBuffer.asFloatBuffer().get(...)}），
 * 不需要任何解析。实践上这才是"检索慢"的主因 —— 慢在反序列化，不在余弦计算本身
 * （1024 维的两两余弦其实只有几毫秒）。
 *
 * <h2>字节序：小端。</h2>
 *
 * <p>{@code ByteOrder.LITTLE_ENDIAN} 与 {@code FloatBuffer} 在主流平台上的原生顺序一致，
 * 写入时可以直接 {@code put(float[])} 批量拷贝，不必逐元素转换。编解码两端必须使用同一字节序，
 * 否则读出来的数值会完全错乱 —— 这是本工具类<b>唯一</b>的隐含约定，故显式写死在此。
 */
public final class Vectors {

    private Vectors() {
        // 工具类，不允许实例化
    }

    /**
     * 向量 → 字节（float32 小端）。
     *
     * @param v 向量；为 null 时返回 null（调用方据此判断"这条没有可用的向量"）
     * @return 长度为 {@code v.length * 4} 的字节数组
     */
    public static byte[] toBytes(float[] v) {
        if (v == null) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asFloatBuffer().put(v);
        return bb.array();
    }

    /**
     * 字节 → 向量（float32 小端）。
     *
     * <p>长度不是 4 的整数倍时返回 null 而不是抛异常：历史数据可能有损坏的字节串，
     * 检索宁可跳过这一条，也不该因为一条脏数据整个查询报错。
     *
     * @param bytes 字节数组；为 null 或空返回 null
     */
    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % 4 != 0) {
            return null;
        }
        float[] out = new float[bytes.length / 4];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }

    /** 解析旧版 JSON 数组文本用（如 {@code [0.0123,-0.456,...]}）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * JSON 数组文本 → 向量。<b>仅供读取历史数据使用</b>，新数据一律走 {@link #toBytes}。
     *
     * <p>存在的意义是"双读兼容"：库里可能还留着改版前写入的 JSON 向量，
     * 在一次性回填完成之前，读取侧必须能认识它们，否则历史文档会突然检索不到。
     *
     * @param json 形如 {@code [0.1,0.2]} 的文本；null / 空白 / 非法格式一律返回 null
     *             （坏数据跳过即可，不该让整个检索报错）
     */
    public static float[] fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var arr = JSON.readTree(json);
            if (!arr.isArray()) {
                return null;
            }
            float[] out = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                out[i] = arr.get(i).isNumber() ? arr.get(i).floatValue() : 0f;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
