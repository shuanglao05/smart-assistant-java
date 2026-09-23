package com.ipas.assistant.service.storage;

import com.ipas.assistant.service.DataDirProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本地文件存储实现的单元测试。
 *
 * <p>这里守两条底线：
 * <ol>
 * <li><b>磁盘布局与历史数据逐字一致</b> —— 目录是 {@code uploads/u{userId}/}、
 * 返回的"相对路径"就是这个形式。库里已有的 {@code files.stored_path} 长这样，
 * 一旦改了就会出现"老文件记录还在、文件却找不到"。所以用断言钉死。</li>
 * <li><b>不许越出数据目录</b> —— 路径来自数据库，必须防路径穿越。</li>
 * </ol>
 *
 * <p>用真实的临时目录测（而不是 mock 掉文件系统）：这类"薄封装"的价值恰恰在于
 * 与文件系统的真实交互，mock 掉就只剩下"我把参数传对了"这种无意义的断言。
 */
class LocalFileStorageTest {

    private Path tempDir;
    private LocalFileStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("ipas-storage-test-");

        DataDirProvider dirProvider = mock(DataDirProvider.class);
        when(dirProvider.dataDir()).thenReturn(tempDir);
        when(dirProvider.uploadsDir()).thenReturn(tempDir.resolve("uploads"));
        storage = new LocalFileStorage(dirProvider);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignore) {
                        /* 清理尽力而为 */
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("put：落在 uploads/u{userId}/ 下，返回的相对路径与历史数据格式一致")
    void putKeepsHistoricalLayout() throws IOException {
        byte[] data = "你好，世界".getBytes(StandardCharsets.UTF_8);

        String storedPath = storage.put(7L, "报告.md", data);

        assertTrue(storedPath.startsWith("uploads/u7/"), "目录必须是 uploads/u{userId}/，实际：" + storedPath);
        assertTrue(storedPath.endsWith(".md"), "必须保留扩展名，实际：" + storedPath);
        Path onDisk = tempDir.resolve(storedPath);
        assertTrue(Files.isRegularFile(onDisk), "文件应真的落盘");
        assertArrayEquals(data, Files.readAllBytes(onDisk), "落盘内容必须与写入一致");
    }

    @Test
    @DisplayName("put：文件名用随机串，避免同名互相覆盖")
    void putUsesRandomNameToAvoidCollision() throws IOException {
        byte[] a = "A".getBytes(StandardCharsets.UTF_8);
        byte[] b = "B".getBytes(StandardCharsets.UTF_8);

        String first = storage.put(1L, "同名.txt", a);
        String second = storage.put(1L, "同名.txt", b);

        assertFalse(first.equals(second), "两次上传同名文件不应得到同一个存储路径");
        assertArrayEquals(a, storage.get(first));
        assertArrayEquals(b, storage.get(second));
    }

    @Test
    @DisplayName("put：不同用户分到不同目录（避免单目录堆几万个文件）")
    void putSeparatesUsers() throws IOException {
        String p1 = storage.put(1L, "a.txt", "x".getBytes(StandardCharsets.UTF_8));
        String p2 = storage.put(2L, "a.txt", "y".getBytes(StandardCharsets.UTF_8));

        assertTrue(p1.startsWith("uploads/u1/"));
        assertTrue(p2.startsWith("uploads/u2/"));
    }

    @Test
    @DisplayName("get / exists：写入后能读回，删除后 exists 变 false")
    void getAndExists() throws IOException {
        String stored = storage.put(3L, "note.txt", "内容".getBytes(StandardCharsets.UTF_8));

        assertTrue(storage.exists(stored));
        assertEquals("内容", new String(storage.get(stored), StandardCharsets.UTF_8));

        storage.delete(stored);
        assertFalse(storage.exists(stored));
    }

    @Test
    @DisplayName("delete：删不存在的文件也算成功（调用方是'尽力清理'语义）")
    void deleteIsIdempotent() {
        assertDoesNotThrowIo(() -> storage.delete("uploads/u9/never-existed.txt"));
    }

    @Test
    @DisplayName("★ 不许越出数据目录：路径穿越直接被拒（get 抛错、exists 返回 false）")
    void rejectsPathTraversal() {
        assertThrows(IOException.class, () -> storage.get("../../outside.txt"));
        assertThrows(IOException.class, () -> storage.get("uploads/../../outside.txt"));
        assertFalse(storage.exists("../../outside.txt"), "非法路径一定'不存在'，不该把异常抛给调用方");
    }

    @Test
    @DisplayName("空路径直接报错（不把 null / 空白当成'数据目录本身'）")
    void rejectsBlankPath() {
        assertThrows(IOException.class, () -> storage.get(""));
        assertThrows(IOException.class, () -> storage.get(null));
        assertThrows(IOException.class, () -> storage.get("   "));
    }

    @Test
    @DisplayName("get：文件不存在时抛 IOException（由调用方转成可读提示）")
    void getMissingFileThrows() {
        assertThrows(IOException.class, () -> storage.get("uploads/u1/missing.txt"));
    }

    /** 断言某个会抛受检异常的调用"不抛异常"。 */
    private static void assertDoesNotThrowIo(ThrowingCall call) {
        try {
            call.run();
        } catch (IOException e) {
            throw new AssertionError("不应抛出异常，实际：" + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws IOException;
    }
}
