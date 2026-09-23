package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话级互斥闸门的单元测试。
 *
 * <p>这些用例守的是"同一会话并发两条流导致串台"这一真实故障。之所以能做成纯单测，
 * 是因为互斥逻辑被刻意抽成了不依赖 Spring / 数据库 / 大模型的独立组件；
 * 若留在聊天服务里（它被排除在自动化测试之外），就只能靠手工联调验证了。
 *
 * <p>断言风格与项目既有测试一致：用 JUnit 5 的 {@code Assertions}。
 */
class ConversationStreamGuardTest {

    @Test
    @DisplayName("同一会话第二次获取占位被拒（409），首次一定成功")
    void secondAcquireIsRejectedWith409() {
        ConversationStreamGuard guard = new ConversationStreamGuard();

        // 第一次：正常拿到
        assertDoesNotThrow(() -> guard.acquire(1L));

        // 第二次（等价于用户双击发送）：必须是 409，且提示可读
        ApiException ex = assertThrows(ApiException.class, () -> guard.acquire(1L));
        assertTrue(ex.getMessage().contains("正在生成"), "提示应说明正在生成，实际：" + ex.getMessage());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("释放后可再次获取（否则用户点了停止就再也发不出去）")
    void acquireSucceedsAfterRelease() {
        ConversationStreamGuard guard = new ConversationStreamGuard();
        guard.acquire(1L);
        guard.release(1L);

        assertDoesNotThrow(() -> guard.acquire(1L));
        assertEquals(1, guard.runningCount());
    }

    @Test
    @DisplayName("不同会话互不影响（互斥是会话级，不是全局级）")
    void differentConversationsDoNotBlockEachOther() {
        ConversationStreamGuard guard = new ConversationStreamGuard();
        guard.acquire(1L);
        assertDoesNotThrow(() -> guard.acquire(2L));
        assertEquals(2, guard.runningCount());
    }

    @Test
    @DisplayName("占位超时后可被接管（防止收尾回调未触发把会话永久锁死）")
    void staleLeaseCanBeTakenOver() {
        ConversationStreamGuard guard = new ConversationStreamGuard();
        long t0 = 1_000_000L;

        assertTrue(guard.tryAcquire(1L, t0), "首次应抢到");
        assertFalse(guard.tryAcquire(1L, t0 + 1_000L), "未超时应拒绝");
        assertTrue(guard.tryAcquire(1L, t0 + ConversationStreamGuard.LEASE_TTL_MS + 1),
                "超过 TTL 应允许接管（宁可放行也不锁死会话）");
    }

    @Test
    @DisplayName("定时清扫只清超时项，不动仍在生成中的会话")
    void sweepRemovesOnlyStaleLeases() {
        ConversationStreamGuard guard = new ConversationStreamGuard();
        long t0 = 5_000_000L;

        guard.tryAcquire(1L, t0);                                        // 会超时
        guard.tryAcquire(2L, t0 + ConversationStreamGuard.LEASE_TTL_MS); // 不会超时

        int removed = guard.sweep(t0 + ConversationStreamGuard.LEASE_TTL_MS + 1);

        assertEquals(1, removed, "只应清掉 1 个超时占位");
        assertEquals(1, guard.runningCount(), "仍在生成中的会话不能被清");
    }

    @Test
    @DisplayName("会话 id 为 null 时安全放行、不抛异常（防御性）")
    void nullConversationIsSafelyIgnored() {
        ConversationStreamGuard guard = new ConversationStreamGuard();
        assertDoesNotThrow(() -> guard.acquire(null));
        assertDoesNotThrow(() -> guard.release(null));
        assertEquals(0, guard.runningCount());
    }
}
