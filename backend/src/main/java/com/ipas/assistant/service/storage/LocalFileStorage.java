package com.ipas.assistant.service.storage;

import com.ipas.assistant.service.DataDirProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 本地磁盘实现（默认）。
 *
 * <h2>磁盘布局与历史数据保持逐字一致</h2>
 *
 * <p>{@code <dataDir>/uploads/u{userId}/{uuid}{ext}}，返回的相对路径是
 * {@code uploads/u{userId}/{uuid}{ext}}。<b>这两点都不能改</b>：
 * 库里已有的 {@code files.stored_path} 就长这样，改了就会变成
 * "老文件的记录还在、文件却找不到"。
 *
 * <p>路径一律通过 {@link DataDirProvider} 解析，不直接读配置 ——
 * 数据目录是运行时可改的，绕过它就会出现"文件存到新目录、却去旧目录读"。
 *
 * <h2>为什么用 {@code @ConditionalOnProperty}</h2>
 *
 * <p>这是"可插拔"的开关：默认（没有配置或配成 local）用本实现；
 * 以后要接对象存储，加一个 {@code @ConditionalOnProperty(..., havingValue = "minio")}
 * 的实现类即可，<b>业务代码一行都不用改</b>。
 */
@Component
@ConditionalOnProperty(name = "app.storage.backend", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStoragePort {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final DataDirProvider dataDirProvider;

    public LocalFileStorage(DataDirProvider dataDirProvider) {
        this.dataDirProvider = dataDirProvider;
    }

    @Override
    public String put(Long userId, String filename, byte[] data) throws IOException {
        Path dir = dataDirProvider.uploadsDir().resolve("u" + userId);
        Files.createDirectories(dir);

        // 用随机名而不是原始文件名：原始名可能重名、可能含非法字符，
        // 前者会互相覆盖，后者在某些文件系统上直接建不出文件。
        // 扩展名保留，便于人工在 data 目录里认出这是什么文件。
        String storedName = UUID.randomUUID().toString().replace("-", "") + lowerExt(filename);
        Files.write(dir.resolve(storedName), data);

        // 相对数据目录的路径（见端口接口的说明：迁移后依然有效）
        return "uploads/u" + userId + "/" + storedName;
    }

    @Override
    public byte[] get(String storedPath) throws IOException {
        return Files.readAllBytes(resolveSafely(storedPath));
    }

    @Override
    public void delete(String storedPath) throws IOException {
        // deleteIfExists：删一个本来就不存在的文件不该算失败（调用方常是"尽力清理"语义）
        Files.deleteIfExists(resolveSafely(storedPath));
    }

    @Override
    public boolean exists(String storedPath) {
        try {
            return Files.exists(resolveSafely(storedPath));
        } catch (IOException e) {
            // 路径非法 = 一定不存在，不必把异常抛给调用方
            return false;
        }
    }

    /**
     * 把"相对路径"解析成绝对路径，并确认它<b>没有跑出数据目录</b>。
     *
     * <p>防的是路径穿越：{@code stored_path} 来自数据库，若有人把它改成
     * {@code ../../某个敏感文件}，没有这道校验就会读到数据目录之外的东西。
     * 正常写入的路径（{@code uploads/u1/xxx.md}）永远在数据目录内，不受影响。
     */
    private Path resolveSafely(String storedPath) throws IOException {
        if (storedPath == null || storedPath.isBlank()) {
            throw new IOException("存储路径为空");
        }
        Path base = dataDirProvider.dataDir().toAbsolutePath().normalize();
        Path resolved = base.resolve(storedPath).normalize();
        if (!resolved.startsWith(base)) {
            log.warn("拒绝越出数据目录的存储路径：{}", storedPath);
            throw new IOException("非法的存储路径");
        }
        return resolved;
    }

    /** 取小写扩展名（含点）；无扩展名返回空串。 */
    private static String lowerExt(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase();
    }
}
