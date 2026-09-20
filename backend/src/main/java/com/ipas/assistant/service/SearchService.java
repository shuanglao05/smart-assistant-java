package com.ipas.assistant.service;

import com.ipas.assistant.dto.SearchDtos;
import com.ipas.assistant.entity.Conversation;
import com.ipas.assistant.entity.Message;
import com.ipas.assistant.repository.ConversationRepository;
import com.ipas.assistant.repository.MessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨会话历史检索。对应 。
 *
 * <h2>它检索的是什么</h2>
 *
 * <p>查的是<b>用户自己的聊天历史</b>（会话标题 + 消息正文），
 * 用的是 <b>LIKE 子串匹配</b>，不是语义检索 ——
 * 语义检索是 RAG 知识库那条路（{@code kb} / {@code rag} 模块），
 * 两者服务的场景完全不同：
 * <ul>
 * <li>这里：用户记得"我前几天跟 AI 聊过某个东西"，去把它翻出来；</li>
 * <li>RAG：拿用户上传的资料回答新问题。</li>
 * </ul>
 *
 * <h2>两类命中、两种排序</h2>
 * <ol>
 * <li><b>标题命中</b>（{@code role = "title"}）—— 每条会话一条，按会话更新时间倒序；</li>
 * <li><b>正文命中</b> —— 按消息时间倒序。</li>
 * </ol>
 * 标题命中排在前面（早期设计就是先追加标题命中、再追加正文命中）。
 * 这个顺序有意义：搜一个词时，用它命名的会话通常正是用户要找的，
 * 比正文里顺口提到一句的两条消息更相关。
 *
 * <h2>为什么要截取摘要而不是返回全文</h2>
 *
 * <p>一条 AI 回答可能上万字。若整条返回，一次检索的响应体可能几 MB，
 * 而前端列表里只显示一两行。所以这里在命中词<b>前后各截一段</b>，
 * 让用户看到上下文就够判断是不是要找的东西了。
 */
@Service
public class SearchService {

 /**
 * 单类命中的条数上限（早期设计 {@code _MAX = 60}）。
 *
 * <p>注意最终的返回条数上限是它的<b>两倍</b>（标题 60 + 正文 60），
 * 因为早期设计最后 {@code return hits[: _MAX * 2]}。
 */
 private static final int MAX_PER_KIND = 60;

 /** 摘要截取长度：命中词前后共取这么多字符左右（早期设计 {@code span = 90}）。 */
 private static final int SNIPPET_SPAN = 90;

 private final ConversationRepository conversationRepository;
 private final MessageRepository messageRepository;

 public SearchService(ConversationRepository conversationRepository,
 MessageRepository messageRepository) {
 this.conversationRepository = conversationRepository;
 this.messageRepository = messageRepository;
 }

 /**
 * 检索。
 *
 * @param userId 当前用户（多用户隔离的唯一屏障）
 * @param q 关键词。为空时直接返回空列表（早期设计靠 数据校验框架 的
 * {@code min_length=1} 挡住，这里在服务层也判一次，
 * 因为空关键词会产生 {@code LIKE '%%'} 全表扫描）
 */
 @Transactional(readOnly = true)
 public List<SearchDtos.SearchHit> search(Long userId, String q) {
 if (q == null || q.trim().isEmpty()) {
 return List.of();
 }
 String keyword = q.trim();
 PageRequest page = PageRequest.of(0, MAX_PER_KIND);

 List<SearchDtos.SearchHit> hits = new ArrayList<>();

 // ---- 1) 标题命中 ----
 for (Conversation c : conversationRepository
 .findByUserIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(userId, keyword, page)) {
 // 标题命中用会话的 updated_at 作为时间（早期设计如此）
 hits.add(SearchDtos.SearchHit.ofTitle(c.getId(), c.getTitle(), c.getUpdatedAt()));
 }

 // ---- 2) 正文命中 ----
 for (Object[] row : messageRepository.searchInUserConversations(userId, keyword, page)) {
 Message m = (Message) row[0];
 String title = (String) row[1];
 hits.add(SearchDtos.SearchHit.ofMessage(m, title, snippet(m.getContent(), keyword)));
 }

 return hits;
 }

 /**
 * 截取命中词前后的一段文本作为摘要。对应 {@code _snippet()}。
 *
 * <p>三个细节都与早期设计一致：
 * <ol>
 * <li>用<b>不区分大小写</b>的方式定位命中位置（用户搜 "React" 也要能命中 "react"）；</li>
 * <li>命中位置靠前时前面不加省略号；靠后时前加 {@code …}，
 * 让用户知道"这段不是从头开始的"；</li>
 * <li>把结果里的<b>换行替换成空格</b> —— 否则摘要里出现换行会把列表行高撑开，
 * 布局会变得很难看。</li>
 * </ol>
 *
 * <p>兜底：万一没找到命中位置（理论上不该发生，因为能返回就说明匹配了；
 * 但数据库的 LIKE 与 Java 的 {@code indexOf} 在字符集/大小写规则上可能有细微差异），
 * 就返回开头的 {@code span * 2} 个字符，至少不是空摘要。
 */
 private static String snippet(String content, String q) {
 if (content == null) {
 return "";
 }
 int idx = content.toLowerCase().indexOf(q.toLowerCase());
 if (idx < 0) {
 return content.substring(0, Math.min(content.length(), SNIPPET_SPAN * 2));
 }
 int start = Math.max(0, idx - SNIPPET_SPAN / 2);
 int end = Math.min(content.length(), idx + q.length() + SNIPPET_SPAN);
 String pre = start > 0 ? "…" : "";
 String post = end < content.length() ? "…" : "";
 return pre + content.substring(start, end).replace("\n", " ").strip() + post;
 }
}
