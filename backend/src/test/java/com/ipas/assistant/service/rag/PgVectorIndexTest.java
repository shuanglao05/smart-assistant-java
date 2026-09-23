package com.ipas.assistant.service.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pgvector 实现的<b>真机集成测试</b>：直接连本机 PostgreSQL（WSL 里的那个）跑。
 *
 * <h2>为什么必须是集成测试</h2>
 *
 * <p>这个类里几乎每行都在跟外部系统打交道：{@code vector} 类型、{@code <=>} 运算符、
 * {@code ON CONFLICT}、HNSW 索引。这些行为<b>没法用 mock 验证</b> ——
 * mock 只能验证"我把 SQL 发过去了"，而真正会出错的是"这条 SQL 在 PG 上到底成不成立"。
 *
 * <h2>PG 没启动时会自动跳过</h2>
 *
 * <p>用 {@link Assumptions#assumeTrue} 探测 5432 端口：连不上就跳过整组用例，
 * 于是<b>没有 PG 的机器上跑 mvn test 依然是绿的</b>，不会把"环境缺失"变成"测试失败"。
 *
 * <h2>⚠️ 凭据不写进代码</h2>
 *
 * <p>密码从环境变量 {@code PGVECTOR_PASSWORD}（或系统属性 {@code pgvector.password}）读，
 * <b>读不到就直接跳过</b>。这样测试文件可以干净地提交进版本库，
 * 也不会出现"测试代码里躺着数据库密码"这种事。本地跑法：
 * <pre>
 * mvn test -Dtest=PgVectorIndexTest -Dpgvector.password=你的密码
 * </pre>
 */
class PgVectorIndexTest {

    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/ipas_vectors";
    private static final String USER = "ipas";
    private static final int DIM = 1024;

    /**
     * 本类用到的全部测试用户 id（9xxx 段，业务上不可能出现）。
     *
     * <p>存在的意义是收尾清理：这个类连的是<b>本机那套真实 PG</b>，
     * 留下的测试向量会污染应用（真实检索会捞到这些假向量），
     * 也会让人对着 `count(*)` 犯迷糊（"怎么比 MySQL 多几条？"）。
     */
    private static final long[] TEST_USER_IDS = {9001L, 9002L, 9003L, 9004L, 9005L, 9006L};

    private PgVectorIndex index;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(pgReachable(), "本机 5432 不可达（WSL 里的 PG 没启动），跳过 pgvector 集成测试");
        String password = System.getProperty("pgvector.password", System.getenv("PGVECTOR_PASSWORD"));
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "未提供 PGVECTOR_PASSWORD，跳过（避免把凭据写进测试代码）");
        index = new PgVectorIndex(URL, USER, password, DIM);
        Assumptions.assumeTrue(index.available(), "pgvector 不可用（扩展/表建不出来），跳过");
    }

    /**
     * 收尾清理：把自己造的数据删干净。
     *
     * <p><b>这一步不能省。</b>曾经漏掉过，结果测试向量留在了真实库里 ——
     * 用户拿 Navicat 一查，看到的全是测试造的单位向量（相似度齐刷刷 1.0000），
     * 既误导了对"检索是否正常"的判断，也让条数对不上 MySQL。
     *
     * <p>初始化被跳过时 {@code index} 为 null，这里也要能安全跳过。
     */
    @AfterEach
    void tearDown() {
        if (index == null) {
            return;
        }
        for (long uid : TEST_USER_IDS) {
            index.deleteByUser(uid);
        }
    }

    private static boolean pgReachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5432), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 造一个"只有第 pos 维是 1、其余为 0"的单位向量，便于精确预测谁的相似度最高。 */
    private static float[] unit(int pos) {
        float[] v = new float[DIM];
        v[pos] = 1f;
        return v;
    }

    @Test
    @DisplayName("建表：available 为 true，且能读到条数")
    void schemaIsReady() {
        long before = index.count(9001L);
        assertTrue(before >= 0, "count 不应返回 -1（说明表和连接都正常）");
    }

    @Test
    @DisplayName("写入 + 检索：最相近的那条应排第一，相似度接近 1")
    void upsertThenSearchRanksNearestFirst() {
        index.deleteByUser(9001L);
        index.upsert(List.of(
                new VectorIndexPort.VectorPoint(900101L, 9001L, 8001L, 7001L, unit(0)),
                new VectorIndexPort.VectorPoint(900102L, 9001L, 8001L, 7001L, unit(500)),
                new VectorIndexPort.VectorPoint(900103L, 9001L, 8002L, 7002L, unit(900))));

        assertEquals(3, index.count(9001L));

        // 用"第 0 维"去查 → 应该命中 900101
        List<VectorIndexPort.Neighbor> hits = index.search(9001L, null, unit(0), 3);

        assertFalse(hits.isEmpty(), "应至少命中一条");
        assertEquals(900101L, hits.get(0).chunkId(), "最相近的片段应排第一");
        assertTrue(hits.get(0).similarity() > 0.99, "同向量的余弦相似度应接近 1，实际 " + hits.get(0).similarity());
    }

    @Test
    @DisplayName("检索按 collection_id 过滤（库内检索语义）")
    void searchFiltersByCollection() {
        index.deleteByUser(9002L);
        index.upsert(List.of(
                new VectorIndexPort.VectorPoint(900201L, 9002L, 8001L, 7001L, unit(0)),
                new VectorIndexPort.VectorPoint(900202L, 9002L, 8002L, 7002L, unit(0))));

        List<VectorIndexPort.Neighbor> only801 = index.search(9002L, 8001L, unit(0), 10);

        assertEquals(1, only801.size(), "限定集合后只应返回该集合的片段");
        assertEquals(900201L, only801.get(0).chunkId());
    }

    @Test
    @DisplayName("幂等 upsert：同一 id 再写不会变成两条")
    void upsertIsIdempotent() {
        index.deleteByUser(9003L);
        VectorIndexPort.VectorPoint p = new VectorIndexPort.VectorPoint(900301L, 9003L, 8001L, 7001L, unit(0));

        index.upsert(List.of(p));
        index.upsert(List.of(p));

        assertEquals(1, index.count(9003L), "主键冲突时应更新而不是插入新行");
    }

    @Test
    @DisplayName("删除：按文档删干净（重新索引前的前置动作）")
    void deleteByFile() {
        index.deleteByUser(9004L);
        index.upsert(List.of(
                new VectorIndexPort.VectorPoint(900401L, 9004L, 8001L, 7001L, unit(0)),
                new VectorIndexPort.VectorPoint(900402L, 9004L, 8001L, 7002L, unit(1))));

        index.deleteByFile(9004L, 7001L);

        assertEquals(1, index.count(9004L), "只应删掉 7001 这个文档的向量");
        List<VectorIndexPort.Neighbor> left = index.search(9004L, null, unit(1), 5);
        assertEquals(900402L, left.get(0).chunkId(), "剩下的应是另一个文档的那条");
    }

    @Test
    @DisplayName("维度不符的查询向量直接返回空（不把脏数据扔给 PG）")
    void searchRejectsWrongDimension() {
        assertTrue(index.search(9001L, null, new float[]{1f, 2f, 3f}, 5).isEmpty(),
                "维度不等于表定义时应自行拦住，而不是让 PG 报错");
    }

    @Test
    @DisplayName("用户隔离：查不到别人的向量")
    void searchIsolatesUsers() {
        index.deleteByUser(9005L);
        index.upsert(List.of(new VectorIndexPort.VectorPoint(900501L, 9006L, 8001L, 7001L, unit(0))));

        assertTrue(index.search(9005L, null, unit(0), 5).isEmpty(), "A 用户不该检索到 B 用户的向量");
    }
}
