package com.ipas.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.dto.SessionDtos;
import com.ipas.assistant.entity.Conversation;
import com.ipas.assistant.entity.FileItem;
import com.ipas.assistant.entity.LlmProvider;
import com.ipas.assistant.entity.Message;
import com.ipas.assistant.repository.ConversationRepository;
import com.ipas.assistant.repository.FileItemRepository;
import com.ipas.assistant.repository.LlmProviderRepository;
import com.ipas.assistant.repository.MessageRepository;
import com.ipas.assistant.service.memory.ConversationMemoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话与消息的业务逻辑。对应 。
 *
 * <h2>本模块最需要注意的三件事</h2>
 *
 * <ol>
 * <li><b>每条查询都必须带 userId</b>。早期设计用一个
 * {@code get_owned_conversation(db, session_id, user_id)} 统一做归属校验，
 * 本类对应 {@link #requireOwned}。漏掉它的后果是「猜到 id 就能看别人的聊天记录」，
 * 而 id 是自增的，猜起来毫无难度。</li>
 *
 * <li><b>删除消息是"成对"的</b>。删掉一条提问会连带删掉它的回答，反之亦然
 * （见 {@link #deleteMessage}）。这样做是为了避免界面上出现孤立的半轮问答气泡。</li>
 *
 * <li><b>多处需要同步操作「模型侧记忆」</b>。这些调用通过
 * {@link ConversationMemoryPort} 发出，第二阶段是空实现
 * （那时还没有 Agent），第三阶段会自动换成真实现。
 * <b>调用点现在就必须留在正确的位置</b> —— 详见该接口的注释。</li>
 * </ol>
 */
@Service
public class SessionService {

 private static final Logger log = LoggerFactory.getLogger(SessionService.class);

 private static final String DEFAULT_TITLE = "新对话";
 private static final String ROLE_USER = "user";
 private static final String ROLE_ASSISTANT = "assistant";

 private final ConversationRepository conversationRepository;
 private final MessageRepository messageRepository;
 private final FileItemRepository fileRepository;
 private final LlmProviderRepository providerRepository;
 private final RuntimeSettingsService settingsService;
 private final AppProperties properties;
 private final ConversationMemoryPort memory;
 private final ObjectMapper objectMapper;

 public SessionService(ConversationRepository conversationRepository,
 MessageRepository messageRepository,
 FileItemRepository fileRepository,
 LlmProviderRepository providerRepository,
 RuntimeSettingsService settingsService,
 AppProperties properties,
 ConversationMemoryPort memory,
 ObjectMapper objectMapper) {
 this.conversationRepository = conversationRepository;
 this.messageRepository = messageRepository;
 this.fileRepository = fileRepository;
 this.providerRepository = providerRepository;
 this.settingsService = settingsService;
 this.properties = properties;
 this.memory = memory;
 this.objectMapper = objectMapper;
 }

 // ==================================================================
 // 会话
 // ==================================================================

 /** 会话列表：按最后更新时间倒序（最近聊过的在最上面）。 */
 @Transactional(readOnly = true)
 public List<SessionDtos.SessionOut> list(Long userId) {
 return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
 .map(SessionDtos.SessionOut::from)
 .toList();
 }

 /**
 * 新建会话。
 *
 * <p>标题缺省为「新对话」；<b>首次提问时后端会自动把它改成问题前 20 个字</b>
 * （那段逻辑在 chat 模块，对应 里判断
 * {@code msg_count == 0 and conv.title == "新对话"}）。
 * 所以这里的默认标题是"还没命名"的标记，不能随便换成别的文案 ——
 * 换了那个自动命名的判断条件就对不上了。
 */
 @Transactional
 public SessionDtos.SessionOut create(Long userId, SessionDtos.CreateRequest payload) {
 String title = (payload == null || payload.title() == null || payload.title().isBlank())
 ? DEFAULT_TITLE
 : payload.title();

 Conversation conv = new Conversation();
 conv.setUserId(userId);
 conv.setTitle(title);
 // provider / model 用 application.yml 的默认值兜底
 conv.setProvider(properties.llm().provider());
 conv.setModel("ollama".equalsIgnoreCase(properties.llm().provider())
 ? properties.llm().ollama().model()
 : settingsService.load(userId).model());

 return SessionDtos.SessionOut.from(conversationRepository.save(conv));
 }

 /**
 * 拉取会话的历史消息，并把附件引用还原成「文件名 + 大小」。
 *
 * <p>为什么要还原：数据库里 {@code messages.ref_file_ids} 只存了附件 id 数组
 * （形如 {@code [3,7]}），而前端要在气泡上方显示<b>可点击的文档名</b>。
 * 这里顺手查一次 files 表补全，避免前端为每条消息再发一次请求
 * （一个长会话可能有几十条带附件的消息，N+1 请求会很明显）。
 */
 @Transactional(readOnly = true)
 public List<SessionDtos.MessageOut> messages(Long userId, Long sessionId) {
 requireOwned(userId, sessionId);

 List<Message> msgs = messageRepository.findByConversationIdOrderByIdAsc(sessionId);
 if (msgs.isEmpty()) {
 return List.of();
 }

 // 先把所有消息引用的附件 id 汇总起来一次性查库（避免逐条查询）
 Map<Long, FileItem> fileCache = new HashMap<>();
 List<Long> allIds = new ArrayList<>();
 for (Message m : msgs) {
 if (ROLE_USER.equals(m.getRole())) {
 allIds.addAll(parseRefIds(m.getRefFileIds()));
 }
 }
 if (!allIds.isEmpty()) {
 for (FileItem f : fileRepository.findByIdInAndUserId(allIds, userId)) {
 fileCache.put(f.getId(), f);
 }
 }

 List<SessionDtos.MessageOut> out = new ArrayList<>(msgs.size());
 for (Message m : msgs) {
 List<SessionDtos.RefFile> refs = new ArrayList<>();
 if (ROLE_USER.equals(m.getRole())) {
 for (Long id : parseRefIds(m.getRefFileIds())) {
 FileItem f = fileCache.get(id);
 if (f != null) {
 refs.add(SessionDtos.RefFile.from(f));
 }
 }
 }
 out.add(SessionDtos.MessageOut.from(m, refs));
 }
 return out;
 }

 /**
 * 更新会话：改标题 / 切模型 / 改启用的技能与知识库。
 *
 * <p><b>{@code provider} 与 {@code providerId} 的联动逻辑不能简化</b>（照抄原文）：
 *
 * <pre>
 * 传了 providerId → 校验该接入属于本用户；provider=cloud；model = 前端选的模型 或 该接入的默认模型
 * 只传 provider=ollama → provider=ollama；providerId=null；model = 前端选的 或 本地默认模型
 * 只传 provider=cloud → 【必须已配置云端凭据】，否则 400；model = 前端选的 或 云端默认模型
 * 两者都没传、只传 model → 仅改 model
 * </pre>
 *
 * <p>「只传 provider=cloud 但没配凭据」要报 400 而不是静默接受：
 * 否则会话会被切到一个不可用的模型上，用户下次提问才炸，而且错误发生在聊天窗口里，
 * 很难联想到是刚才在设置里切换模型造成的。
 *
 * <p>技能 / 知识库变化后要清掉该会话缓存的 Agent —— 否则下次发言仍走旧配置，
 * 表现为「勾了技能却不生效」。
 */
 @Transactional
 public SessionDtos.SessionOut update(Long userId, Long sessionId, SessionDtos.UpdateRequest payload) {
 Conversation conv = requireOwned(userId, sessionId);

 if (payload.title() != null) {
 conv.setTitle(payload.title());
 }

 if (payload.provider() != null || payload.providerId() != null) {
 if (payload.providerId() != null) {
 // 走「已接入的云端平台」
 LlmProvider provider = providerRepository
 .findByIdAndUserId(payload.providerId(), userId)
 .orElseThrow(() -> ApiException.badRequest("该 API 接入不存在"));
 conv.setProvider("cloud");
 conv.setProviderId(provider.getId());
 // 用前端选中的具体模型；未指定才回落到该接入的默认模型
 conv.setModel(notBlank(payload.model()) ? payload.model().trim() : provider.getModel());
 } else {
 String p = notBlank(payload.provider()) ? payload.provider().trim().toLowerCase() : "cloud";
 if ("ollama".equals(p)) {
 conv.setProvider("ollama");
 conv.setProviderId(null);
 conv.setModel(notBlank(payload.model())
 ? payload.model()
 : properties.llm().ollama().model());
 } else {
 RuntimeSettingsService.LlmSettings s = settingsService.load(userId);
 if (s.apiKey().isBlank() || s.baseUrl().isBlank()) {
 throw ApiException.badRequest(
 "云端模型未配置：请在设置里接入 API 或配置云端凭据");
 }
 conv.setProvider("cloud");
 conv.setProviderId(null);
 conv.setModel(notBlank(payload.model()) ? payload.model() : s.model());
 }
 }
 } else if (payload.model() != null) {
 // 只改模型名（provider 不变）
 conv.setModel(payload.model());
 }

 if (payload.activeSkillIds() != null) {
 conv.setActiveSkillIds(new ArrayList<>(payload.activeSkillIds()));
 // 技能集变了 → 缓存的 Agent 不再适用
 memory.evictAgent(sessionId);
 }
 if (payload.activeKbIds() != null) {
 conv.setActiveKbIds(new ArrayList<>(payload.activeKbIds()));
 // 知识库集变了 → 检索范围变了，同样要重建
 memory.evictAgent(sessionId);
 }

 // ⚠️ 必须先 flush 再构造响应，否则返回的 updated_at 是【旧值】。
 //
 // 原因：updatedAt 由实体上的 @PreUpdate 回调维护，而它只在 Hibernate
 // 「刷写」实体时才触发；而刷写默认发生在事务提交的那一刻。
 // 如果直接在方法里构造 DTO，拿到的还是加载时那个旧时间戳。
 //
 // 早期设计没有这个问题 —— 它的 update_session 结尾是
 // `db.commit(); db.refresh(conv)`，refresh 会把更新后的时间读回来。
 //
 // 为什么要较真：session 列表是按 updated_at 倒序排的。
 // 前端改完标题后通常用这个返回值就地更新列表，若时间戳是旧的，
 // 刚改过的会话不会跳到最前面，用户会以为"改名没生效"。
 conversationRepository.flush();

 return SessionDtos.SessionOut.from(conv);
 }

 /**
 * 删除会话：连同它的全部消息、缓存的 Agent、以及共享记忆里的历史。
 *
 * <p>清理记忆这一步不能省 —— 否则删掉的会话仍占着内存，
 * 而且如果将来 id 被复用（MySQL 自增不会，但清库/导入数据后可能），
 * 新会话会莫名其妙地"记得"旧对话。
 */
 @Transactional
 public void delete(Long userId, Long sessionId) {
 Conversation conv = requireOwned(userId, sessionId);
 messageRepository.deleteByConversationId(sessionId);
 conversationRepository.delete(conv);

 memory.evictAgent(sessionId);
 memory.clearMemory(sessionId);
 }

 /**
 * 删除会话内的某一条消息 —— <b>成对删除</b>。
 *
 * <p>配对规则（照抄原文）：
 * <ul>
 * <li>删的是<b>用户提问</b> → 连带删掉它之后的第一条助手回答；</li>
 * <li>删的是<b>助手回答</b> → 连带删掉它之前的最后一条用户提问。</li>
 * </ul>
 * 这样界面上不会留下孤立的半轮问答（只有问没有答，或反过来）。
 *
 * <p>删完之后还要<b>按剩余消息重建记忆线程</b>：
 * 否则模型仍然"记得"用户刚删掉的内容 —— 用户会看到
 * 「我把那条消息删了，但 AI 还在提这件事」这种非常诡异的现象。
 */
 @Transactional
 public SessionDtos.DeleteMessagesResult deleteMessage(Long userId, Long sessionId, Long messageId) {
 Conversation conv = requireOwned(userId, sessionId);
 Message msg = messageRepository.findByIdAndConversationId(messageId, sessionId)
 .orElseThrow(() -> ApiException.notFound("消息不存在"));

 List<Long> toDelete = new ArrayList<>();
 toDelete.add(msg.getId());

 // 找出"同一轮的另一条"
 Optional<Message> partner;
 if (ROLE_USER.equals(msg.getRole())) {
 partner = messageRepository.findFirstByConversationIdAndRoleAndIdGreaterThanOrderByIdAsc(
 sessionId, ROLE_ASSISTANT, msg.getId());
 } else {
 partner = messageRepository.findFirstByConversationIdAndRoleAndIdLessThanOrderByIdDesc(
 sessionId, ROLE_USER, msg.getId());
 }
 partner.ifPresent(p -> toDelete.add(p.getId()));

 messageRepository.deleteAllById(toDelete);

 // 按剩余消息重建记忆（拿到的是"删完之后"的列表，所以要在删除之后查）
 rebuildMemoryAfterDeletion(userId, sessionId, conv);

 return new SessionDtos.DeleteMessagesResult("deleted", toDelete);
 }

 /**
 * 清空当前用户的全部会话及其消息。
 *
 * <p>返回值里的 {@code count} 是<b>清理前</b>的会话数，
 * 前端据此给用户一个"N 个会话已清空"的反馈。
 */
 @Transactional
 public SessionDtos.ClearAllResult deleteAll(Long userId) {
 List<Conversation> convs = conversationRepository.findAllByUserId(userId);
 int count = convs.size();
 if (count == 0) {
 return new SessionDtos.ClearAllResult("deleted", 0);
 }

 List<Long> ids = convs.stream().map(Conversation::getId).toList();
 for (Long id : ids) {
 messageRepository.deleteByConversationId(id);
 }
 conversationRepository.deleteAllById(ids);

 for (Long id : ids) {
 memory.clearMemory(id);
 memory.evictAgent(id);
 }
 log.info("已清空用户 {} 的全部会话，共 {} 个", userId, count);
 return new SessionDtos.ClearAllResult("deleted", count);
 }

 // ==================================================================
 // 内部辅助
 // ==================================================================

 /**
 * 按"删完之后"剩余的的消息重建记忆线程。
 *
 * <p>用剩余消息重新喂给模型侧的记忆，替代原来那条已被污染的历史。
 * 任何异常都<b>只记日志不抛出</b>：重建失败最多是"上下文从这一点起重置"，
 * 绝不该让"删一条消息"这个操作整体失败 —— 数据已经删了，
 * 再报错只会让用户困惑（并可能重试造成误删）。
 */
 private void rebuildMemoryAfterDeletion(Long userId, Long sessionId, Conversation conv) {
 try {
 List<Message> remaining = messageRepository.findByConversationIdOrderByIdAsc(sessionId);
 List<String[]> rows = new ArrayList<>(remaining.size());
 for (Message m : remaining) {
 rows.add(new String[]{m.getRole(), m.getContent()});
 }
 memory.clearMemory(sessionId);
 memory.rebuildMemory(sessionId, userId, conv.getProvider(), conv.getModel(), rows);
 } catch (Exception e) {
 log.warn("删除消息后重建会话记忆失败，将退化为清空该会话记忆（上下文从该点重置）: {}",
 e.getMessage());
 memory.clearMemory(sessionId);
 }
 }

 /**
 * 解析 {@code ref_file_ids} 里存的 JSON 数组。
 *
 * <p>容错处理（与原文一致）：非 JSON、非数组、元素不是数字，一律按"没有附件"处理。
 * 这些数据是历史遗留的，格式可能不干净，但<b>绝不能因为一条附件的脏数据
 * 就让整个会话的消息列表打不开</b>。
 */
 private List<Long> parseRefIds(String refFileIds) {
 if (refFileIds == null || refFileIds.isBlank()) {
 return List.of();
 }
 try {
 var node = objectMapper.readTree(refFileIds);
 if (node == null || !node.isArray()) {
 return List.of();
 }
 List<Long> ids = new ArrayList<>();
 for (var item : node) {
 if (item.isNumber()) {
 ids.add(item.asLong());
 } else if (item.isTextual()) {
 try {
 ids.add(Long.parseLong(item.asText().trim()));
 } catch (NumberFormatException ignored) {
 // 单个元素不合法就跳过这个元素，不影响其它
 }
 }
 }
 return ids;
 } catch (Exception e) {
 log.debug("ref_file_ids 解析失败，按无附件处理：{}", refFileIds);
 return List.of();
 }
 }

 /** 归属校验：不属于该用户就 404（而不是 403，避免泄露"这个会话存在"）。 */
 private Conversation requireOwned(Long userId, Long sessionId) {
 return conversationRepository.findByIdAndUserId(sessionId, userId)
 .orElseThrow(() -> ApiException.notFound("会话不存在"));
 }

 private static boolean notBlank(String s) {
 return s != null && !s.isBlank();
 }
}
