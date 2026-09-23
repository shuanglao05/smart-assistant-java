package com.ipas.assistant.service.storage;

import java.io.IOException;

/**
 * 文件存储端口：业务层只跟它打交道，<b>不认识磁盘、也不认识任何对象存储</b>。
 *
 * <h2>为什么需要这层抽象</h2>
 *
 * <p>抽象之前，上传文件的读写散落在业务代码里：{@code FileService} 直接
 * {@code Files.write}、{@code KbService} 直接 {@code Files.delete}，
 * 两边各自拼路径。这带来三个问题：
 * <ol>
 * <li><b>换存储后端要改业务代码</b>：想接对象存储（S3 / MinIO / 云 OSS），
 * 得把所有碰磁盘的地方找出来改一遍；</li>
 * <li><b>知识库逻辑被磁盘绑架</b>：删知识库要删文件，于是
 * {@code KbService} 也依赖了 {@code java.nio.file}，没法在纯内存里测；</li>
 * <li><b>路径语义容易被改坏</b>：{@code stored_path} 存的是"相对数据目录"的路径
 * （这样整个数据目录搬走后记录依然有效），谁拼错一处就会出现
 * "上传成功、重索引却说文件不存在"。</li>
 * </ol>
 *
 * <p>收敛成一个端口后：业务层只说"存进去""取出来""删掉"，
 * 至于落在本地磁盘还是对象存储，由实现决定。
 *
 * <h2>返回值为什么是"相对路径"而不是绝对路径</h2>
 *
 * <p>{@link #put} 返回的相对路径会原样存进 {@code files.stored_path}。
 * 相对路径让数据目录整体迁移后记录依然有效 —— 这是本项目既有数据格式的一部分，
 * <b>任何实现都必须遵守</b>（本地实现返回 {@code uploads/u1/xxx.md}，
 * 对象存储实现返回的应是同级语义的"对象键"）。
 */
public interface FileStoragePort {

    /**
     * 存入一份文件，返回它的<b>相对路径</b>（业务层只认这个字符串）。
     *
     * @param userId   归属用户（用于分目录，避免单个目录堆几万个文件）
     * @param filename 原始文件名，实现据此保留扩展名（便于排查与人工识别）
     * @param data     文件内容
     * @return 相对路径，例如 {@code uploads/u1/9f3c...a.md}
     */
    String put(Long userId, String filename, byte[] data) throws IOException;

    /** 读出一份文件的全部内容。文件不存在时抛 {@link IOException}。 */
    byte[] get(String storedPath) throws IOException;

    /** 删除一份文件；文件本就不存在时视为成功（幂等）。 */
    void delete(String storedPath) throws IOException;

    /** 文件是否存在。 */
    boolean exists(String storedPath);
}
