package com.ipas.assistant.service.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MinIO 对象存储实现（仅在 {@code app.storage.backend=minio} 时启用）。
 *
 * <h2>它只做一件事</h2>
 *
 * <p>只保存<b>用户上传的原始文件字节</b>。抽取出来的正文在 MySQL
 * （{@code files.content}）、切片文本在 {@code kb_chunks.text}、向量在
 * {@code kb_chunks.embedding_bin} —— 这些都与本类无关。
 * 对象存储不是数据库，它不替代任何现有存储。
 *
 * <h2>★ 对象键与本地实现保持同一语义</h2>
 *
 * <p>本地实现存 {@code <dataDir>/uploads/u{userId}/{uuid}{ext}}，返回相对路径
 * {@code uploads/u{userId}/{uuid}{ext}}；本实现把<b>同一个字符串当作对象键</b>。
 * 这样做的价值很实际：
 * <ul>
 * <li>{@code files.stored_path} 的语义完全不变，切后端不需要改数据；</li>
 * <li>历史文件迁移变得平凡 —— 把 {@code uploads/} 下的文件按相对路径原样传成对象即可；</li>
 * <li>将来还可以做"双读"（先找对象、找不到回落本地磁盘）实现平滑过渡。</li>
 * </ul>
 *
 * <h2>为什么配置用 {@code @Value} 而不是进 AppProperties</h2>
 *
 * <p>这些配置<b>只有本类用</b>，而且属于"可插拔实现"的私有配置。
 * 放进核心的 {@link com.ipas.assistant.config.AppProperties} 会迫使所有构造
 * AppProperties 的地方（含多个测试）跟着改，收益却只是"看起来统一"。
 * 放在实现类上更内聚。
 *
 * <h2>⚠️ 部署提醒</h2>
 * <p>access-key / secret-key 属于凭据，<b>走环境变量传入，不要提交进版本库</b>。
 */
@Component
@ConditionalOnProperty(name = "app.storage.backend", havingValue = "minio")
public class MinioFileStorage implements FileStoragePort {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStorage.class);

    /** 对象键的固定前缀，与本地实现的目录名对齐（见类注释）。 */
    private static final String PREFIX = "uploads";

    private final MinioClient client;
    private final String bucket;

    /** 桶只检查/创建一次（每次调用都问一遍 MinIO 纯属浪费）。 */
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    public MinioFileStorage(
            @Value("${app.storage.minio.endpoint:http://127.0.0.1:9000}") String endpoint,
            @Value("${app.storage.minio.access-key:}") String accessKey,
            @Value("${app.storage.minio.secret-key:}") String secretKey,
            @Value("${app.storage.minio.bucket:ipas-uploads}") String bucket) {
        MinioClient.Builder builder = MinioClient.builder().endpoint(endpoint);
        if (accessKey != null && !accessKey.isBlank()) {
            builder.credentials(accessKey, secretKey);
        }
        this.client = builder.build();
        this.bucket = bucket;
        log.info("文件存储后端：MinIO（endpoint={} bucket={}）", endpoint, bucket);
    }

    @Override
    public String put(Long userId, String filename, byte[] data) throws IOException {
        ensureBucket();
        String objectName = PREFIX + "/u" + userId + "/"
                + UUID.randomUUID().toString().replace("-", "") + lowerExt(filename);
        try (InputStream in = new ByteArrayInputStream(data)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(in, data.length, -1) // -1 = 不限制分片大小，由 SDK 决定
                    .contentType(contentTypeOf(filename))
                    .build());
        } catch (Exception e) {
            throw new IOException("上传到 MinIO 失败：" + e.getMessage(), e);
        }
        return objectName;
    }

    @Override
    public byte[] get(String storedPath) throws IOException {
        requireKey(storedPath);
        try (GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(storedPath)
                .build())) {
            return response.readAllBytes();
        } catch (Exception e) {
            // 不区分"不存在"与"读失败"：对调用方来说都只能报"读取失败"，区分没有意义
            throw new IOException("从 MinIO 读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String storedPath) throws IOException {
        requireKey(storedPath);
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(storedPath)
                    .build());
        } catch (Exception e) {
            throw new IOException("从 MinIO 删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return false;
        }
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(storedPath)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 桶不存在就建（幂等；S3 语义里"建桶"本身也是幂等的）。 */
    private void ensureBucket() throws IOException {
        if (bucketReady.get()) {
            return;
        }
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("已创建 MinIO 桶：{}", bucket);
            }
            bucketReady.set(true);
        } catch (Exception e) {
            throw new IOException("MinIO 桶不可用（" + bucket + "）：" + e.getMessage(), e);
        }
    }

    private static void requireKey(String storedPath) throws IOException {
        if (storedPath == null || storedPath.isBlank()) {
            throw new IOException("存储路径为空");
        }
    }

    /** 取小写扩展名（含点）；无扩展名返回空串。与本地实现保持同一规则。 */
    private static String lowerExt(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase();
    }

    /**
     * 按扩展名给个 Content-Type。
     *
     * <p>它的实际作用：浏览器直接访问对象时能正确预览（我们目前都走应用接口下载，
     * 所以只是"顺手做对"）。认不出来的类型统一给 {@code application/octet-stream}。
     */
    private static String contentTypeOf(String filename) {
        String ext = lowerExt(filename);
        return switch (ext) {
            case ".pdf" -> "application/pdf";
            case ".txt", ".md" -> "text/plain; charset=utf-8";
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".json" -> "application/json";
            case ".csv" -> "text/csv; charset=utf-8";
            default -> "application/octet-stream";
        };
    }
}
