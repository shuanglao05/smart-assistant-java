package com.ipas.assistant.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PostgreSQL + pgvector 实现（仅在 {@code app.rag.vector-store=pgvector} 时启用）。
 *
 * <h2>它只做一件事</h2>
 *
 * <p>按 {@link VectorIndexPort} 的约定，只存 {@code chunk_id} + 向量（外加用于过滤的
 * user/collection/file_id）。正文仍在 MySQL —— 见端口接口的说明。
 *
 * <h2>为什么用 DriverManager 而不是再配一个 DataSource</h2>
 *
 * <p>Spring Boot 里再配一个 {@code DataSource} 需要处理 {@code @Primary} 与 JPA 的绑定，
 * 稍不注意就会让 JPA 连错库（那种错误在启动时就报，但排查方向很容易搞错）。
 * 而本类的访问量很小（索引时批量写、检索时每轮一个查询），
 * 直接用 {@link DriverManager} 按需取连接、用完就还，反而最简单也最不容易出错。
 *
 * <p>代价：没有连接池，每次都有一次 TCP 连接开销。对本地 PG 是毫秒级，
 * 可以接受；将来真需要池化，把这里换成 HikariDataSource 即可，接口不变。
 *
 * <h2>与 MySQL 的关系：可退让</h2>
 *
 * <p>PG 没启动 / 表没建好时，{@link #available()} 返回 false，调用方回落到 MySQL 检索。
 * <b>外部依赖缺席不该让功能失效</b> —— 这是本类所有方法都要守的约定。
 */
@Component
@ConditionalOnProperty(name = "app.rag.vector-store", havingValue = "pgvector")
public class PgVectorIndex implements VectorIndexPort {

    private static final Logger log = LoggerFactory.getLogger(PgVectorIndex.class);

    /** 建库失败后的冷却时间：避免每次检索都去试连一个已经挂掉的 PG。 */
    private static final long RETRY_AFTER_MS = 30_000L;

    private final String url;
    private final String user;
    private final String password;
    /** 向量维度。必须与嵌入模型一致（bge-m3 = 1024），不一致时插入会被 PG 直接拒绝。 */
    private final int dimension;

    private final AtomicBoolean schemaReady = new AtomicBoolean(false);
    private volatile long lastFailMs = 0L;

    public PgVectorIndex(
            @Value("${app.rag.pgvector.url:jdbc:postgresql://127.0.0.1:5432/ipas_vectors}") String url,
            @Value("${app.rag.pgvector.user:}") String user,
            @Value("${app.rag.pgvector.password:}") String password,
            @Value("${app.rag.pgvector.dimension:1024}") int dimension) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.dimension = dimension;
        log.info("向量检索后端：pgvector（url={} 维度={}）", url, dimension);
    }

    // ==================================================================
    // 可用性
    // ==================================================================

    @Override
    public boolean available() {
        if (schemaReady.get()) {
            return true;
        }
        if (System.currentTimeMillis() - lastFailMs < RETRY_AFTER_MS) {
            return false; // 冷却中，别每次都去试连
        }
        synchronized (this) {
            if (schemaReady.get()) {
                return true;
            }
            try {
                ensureSchema();
                schemaReady.set(true);
                log.info("pgvector 索引就绪（表 kb_vectors / 维度 {}）", dimension);
                return true;
            } catch (Exception e) {
                lastFailMs = System.currentTimeMillis();
                log.warn("pgvector 不可用，检索将回落到 MySQL：{}", e.getMessage());
                return false;
            }
        }
    }

    /** 建扩展、建表、建索引。全部幂等，可重复执行。 */
    private void ensureSchema() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            // 扩展必须先有：vector 类型由它提供
            st.execute("CREATE EXTENSION IF NOT EXISTS vector");
            st.execute("CREATE TABLE IF NOT EXISTS kb_vectors ("
                    + "  chunk_id      BIGINT PRIMARY KEY,"
                    + "  user_id       BIGINT NOT NULL,"
                    + "  collection_id BIGINT,"
                    + "  file_id       BIGINT NOT NULL,"
                    + "  embedding     vector(" + dimension + ") NOT NULL"
                    + ")");
            // 过滤用的普通索引：检索总是带 user_id（+ 可能的 collection_id）
            st.execute("CREATE INDEX IF NOT EXISTS idx_kb_vectors_scope "
                    + "ON kb_vectors (user_id, collection_id)");
            // 近邻检索索引。数据量小时用不上，但它让"涨到十万级"时无需任何改动。
            // vector_cosine_ops 必须与查询里使用的 <=> 运算符一致，否则索引不会被使用。
            st.execute("CREATE INDEX IF NOT EXISTS idx_kb_vectors_embedding "
                    + "ON kb_vectors USING hnsw (embedding vector_cosine_ops)");
        }
    }

    private Connection open() throws Exception {
        if (user == null || user.isBlank()) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, user, password);
    }

    // ==================================================================
    // 写入
    // ==================================================================

    @Override
    public void upsert(List<VectorPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        if (!available()) {
            // 写入侧失败不抛异常：索引是派生物，MySQL 里已经有真相，
            // 这里没写进去最多是"这次检索退化成 MySQL 路径"，不该让上传/索引整体失败。
            log.warn("pgvector 不可用，跳过 {} 条向量写入（MySQL 数据不受影响）", points.size());
            return;
        }
        String sql = "INSERT INTO kb_vectors (chunk_id, user_id, collection_id, file_id, embedding) "
                + "VALUES (?, ?, ?, ?, ?::vector) "
                + "ON CONFLICT (chunk_id) DO UPDATE SET "
                + "  user_id = EXCLUDED.user_id, collection_id = EXCLUDED.collection_id, "
                + "  file_id = EXCLUDED.file_id, embedding = EXCLUDED.embedding";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (VectorPoint p : points) {
                ps.setLong(1, p.chunkId());
                ps.setLong(2, p.userId());
                if (p.collectionId() == null) {
                    ps.setNull(3, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(3, p.collectionId());
                }
                ps.setLong(4, p.fileId());
                ps.setString(5, toVectorLiteral(p.vector()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            log.warn("写入 pgvector 失败（不影响 MySQL 中的片段）：{}", e.getMessage());
        }
    }

    @Override
    public void deleteByFile(Long userId, Long fileId) {
        exec("DELETE FROM kb_vectors WHERE user_id = ? AND file_id = ?", userId, fileId);
    }

    @Override
    public void deleteByUser(Long userId) {
        exec("DELETE FROM kb_vectors WHERE user_id = ?", userId);
    }

    @Override
    public void clear() {
        if (!available()) {
            return;
        }
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE kb_vectors");
        } catch (Exception e) {
            log.warn("清空 pgvector 失败：{}", e.getMessage());
        }
    }

    @Override
    public long count(Long userId) {
        if (!available()) {
            return -1;
        }
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM kb_vectors WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            log.warn("统计 pgvector 条数失败：{}", e.getMessage());
            return -1;
        }
    }

    // ==================================================================
    // 检索
    // ==================================================================

    @Override
    public List<Neighbor> search(Long userId, Long collectionId, float[] query, int topK) {
        if (!available() || query == null || query.length != dimension) {
            return List.of();
        }
        // 余弦距离用 <=>，返回值是"距离"（越小越像），所以相似度 = 1 - 距离。
        // ORDER BY 必须写成向量表达式本身（而不是上面那个别名），否则用不到 HNSW 索引。
        String sql = "SELECT chunk_id, 1 - (embedding <=> ?::vector) AS similarity FROM kb_vectors "
                + "WHERE user_id = ? "
                + (collectionId == null ? "" : "AND collection_id = ? ")
                + "ORDER BY embedding <=> ?::vector LIMIT ?";
        String literal = toVectorLiteral(query);
        List<Neighbor> hits = new java.util.ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, literal);
            ps.setLong(i++, userId);
            if (collectionId != null) {
                ps.setLong(i++, collectionId);
            }
            ps.setString(i++, literal);
            ps.setInt(i, Math.max(1, topK));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hits.add(new Neighbor(rs.getLong("chunk_id"), rs.getDouble("similarity")));
                }
            }
        } catch (Exception e) {
            log.warn("pgvector 检索失败，本次将回落到 MySQL：{}", e.getMessage());
            return List.of();
        }
        return hits;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private void exec(String sql, Long... args) {
        if (!available()) {
            return;
        }
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setLong(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("pgvector 删除失败（索引为派生物，可后续通过重建修正）：{}", e.getMessage());
        }
    }

    /**
     * float[] → pgvector 的字面量，形如 {@code [0.1,-0.25,3]}。
     *
     * <p>不用 {@code String.format}（它对每个元素都要解析格式串，几千个向量时会明显拖慢）；
     * 直接拼接即可，数值本身由 {@link Float#toString} 保证可被 PG 解析。
     */
    private static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
