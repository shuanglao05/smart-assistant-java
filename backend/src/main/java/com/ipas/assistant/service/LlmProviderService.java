package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.dto.LlmProviderDtos;
import com.ipas.assistant.entity.LlmProvider;
import com.ipas.assistant.repository.LlmProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 多平台模型接入的业务逻辑。对应 。
 *
 * <h2>这个模块存在的意义</h2>
 *
 * <p>早期方案只能配一套云端凭据（{@code CLOUD_*}）。接入第二个平台时，
 * 用户必须把第一个的配置覆盖掉 —— 想来回切换就得反复改配置。
 * 本模块让每个平台各存一套凭据，对话时按会话选用，互不影响。
 *
 * <h2>核心设计：保存前必须真实测连</h2>
 *
 * <p>{@code create} 与 {@code update} 都会先调
 * {@link LlmConnectivityService#testCloud}，<b>不通过就不入库</b>。
 * 这样做是为了消除一种很糟的体验：用户填了个错的 Key，界面提示"保存成功"，
 * 于是放心地去对话，结果第一次提问才报错 —— 而且错误信息出现在聊天窗口里，
 * 用户很难联想到是刚才配置的问题。
 *
 * <p>测连失败时返回的 400 里会带上平台返回的<b>原始错误内容</b>，
 * 让用户能区分"Key 错了"还是"模型名不存在"还是"网络不通"。
 */
@Service
public class LlmProviderService {

 private static final Logger log = LoggerFactory.getLogger(LlmProviderService.class);

 private final LlmProviderRepository repository;
 private final LlmConnectivityService connectivity;
 private final RuntimeSettingsService settingsService;

 public LlmProviderService(LlmProviderRepository repository,
 LlmConnectivityService connectivity,
 RuntimeSettingsService settingsService) {
 this.repository = repository;
 this.connectivity = connectivity;
 this.settingsService = settingsService;
 }

 /** 列出已接入的平台（Key 脱敏）。 */
 @Transactional(readOnly = true)
 public List<LlmProviderDtos.Out> list(Long userId) {
 return repository.findByUserIdOrderByIdAsc(userId).stream()
 .map(this::toOut)
 .toList();
 }

 /**
 * 接入新平台：重名校验 → 真实测连 → 入库。
 */
 @Transactional
 public LlmProviderDtos.Out create(Long userId, LlmProviderDtos.Create payload) {
 String name = payload.name().trim();

 // 同一用户下名称唯一 —— 前端下拉里用名称区分平台，重名会让用户分不清
 if (repository.findByUserIdOrderByIdAsc(userId).stream()
 .anyMatch(p -> name.equals(p.getName()))) {
 throw ApiException.badRequest("已存在名为「" + name + "」的接入，请换一个名称");
 }

 String baseUrl = payload.baseUrl().trim();
 String apiKey = payload.apiKey().trim();
 String model = payload.model().trim();

 // 测连不通过就不入库（见类注释）
 try {
 connectivity.testCloud(apiKey, baseUrl, model, settingsService.load(userId));
 } catch (CloudHttpGateway.HttpCallException e) {
 throw ApiException.badRequest(e.getMessage());
 }

 LlmProvider row = new LlmProvider();
 row.setUserId(userId);
 row.setName(name);
 row.setBaseUrl(baseUrl);
 row.setApiKey(apiKey);
 row.setModel(model);
 row.setModels(RuntimeSettingsService.joinModelList(payload.models()));

 LlmProvider saved = repository.save(row);
 log.info("已接入云端平台 userId={} name={} baseUrl={}", userId, name, baseUrl);
 return toOut(saved);
 }

 /**
 * 编辑已接入的平台。
 *
 * <p><b>字段的"缺省"语义全部是"保持原值"</b>（与早期设计一致）：
 * 每项都做 {@code payload.x != null ? payload.x : row.x} 的回落。
 * 尤其 {@code apiKey}：前端编辑时只显示掩码、不回显明文，
 * 用户不动它就提交空串 —— 必须回落到原 Key，否则一编辑就把凭据清空了。
 */
 @Transactional
 public LlmProviderDtos.Out update(Long userId, Long pid, LlmProviderDtos.Update payload) {
 LlmProvider row = repository.findByIdAndUserId(pid, userId)
 .orElseThrow(() -> ApiException.notFound("未找到该接入"));

 String newName = payload.name() != null ? payload.name().trim() : row.getName();
 String newBase = payload.baseUrl() != null ? payload.baseUrl().trim() : row.getBaseUrl();
 String newModel = payload.model() != null ? payload.model().trim() : row.getModel();
 // 空串或不传都表示"不修改 Key"
 String newKey = (payload.apiKey() != null && !payload.apiKey().isBlank())
 ? payload.apiKey().trim()
 : row.getApiKey();

 // 改名时才做重名校验（没改名就没必要查）
 if (!newName.equals(row.getName())) {
 boolean dup = repository.findByUserIdOrderByIdAsc(userId).stream()
 .anyMatch(p -> !p.getId().equals(pid) && newName.equals(p.getName()));
 if (dup) {
 throw ApiException.badRequest("已存在名为「" + newName + "」的接入");
 }
 }

 try {
 connectivity.testCloud(newKey, newBase, newModel, settingsService.load(userId));
 } catch (CloudHttpGateway.HttpCallException e) {
 throw ApiException.badRequest(e.getMessage());
 }

 row.setName(newName);
 row.setBaseUrl(newBase);
 row.setApiKey(newKey);
 row.setModel(newModel);
 if (payload.models() != null) {
 row.setModels(RuntimeSettingsService.joinModelList(payload.models()));
 }

 // 先 flush 再构造响应：updatedAt 由 @PreUpdate 维护，刷写默认发生在事务提交时，
 // 不 flush 会返回旧时间戳（早期设计靠 db.refresh 避开）。
 repository.flush();

 return toOut(row);
 }

 /**
 * 删除接入。
 *
 * <p>不需要清理引用它的会话：{@code conversations.provider_id} 会变成一个
 * 指向不存在记录的 id，而读取时（第三阶段构建 Agent）会查不到 provider，
 * 于是自动回落到默认配置或本地 Ollama —— 这正是早期设计注释里说的
 * 「正在引用它的会话会回落到默认配置，不报错」。
 *
 * <p>这里刻意<b>不做级联清理</b>：把用户的历史会话改成"没有 provider"
 * 反而是破坏性的（用户可能只是临时删掉想重新接入），保留 id 更安全。
 */
 @Transactional
 public void delete(Long userId, Long pid) {
 LlmProvider row = repository.findByIdAndUserId(pid, userId)
 .orElseThrow(() -> ApiException.notFound("未找到该接入"));
 repository.delete(row);
 }

 /**
 * 单独测试某个已接入平台。对应 {@code POST /api/llm-providers/{id}/test}。
 *
 * <p><b>恒返回 200</b>，结果放 {@code ok}/{@code message} —— 前端把 message
 * 直接显示在卡片上，不必区分"请求失败"与"测试不通过"。
 */
 @Transactional(readOnly = true)
 public LlmProviderDtos.TestResult test(Long userId, Long pid) {
 LlmProvider row = repository.findByIdAndUserId(pid, userId)
 .orElseThrow(() -> ApiException.notFound("未找到该接入"));
 try {
 connectivity.testCloud(row.getApiKey(), row.getBaseUrl(), row.getModel(),
 settingsService.load(userId));
 } catch (CloudHttpGateway.HttpCallException e) {
 return new LlmProviderDtos.TestResult(false, e.getMessage());
 }
 return new LlmProviderDtos.TestResult(true, "连接成功（" + row.getModel() + "）");
 }

 /** 实体 → 响应（顺带脱敏）。 */
 private LlmProviderDtos.Out toOut(LlmProvider p) {
 return LlmProviderDtos.Out.from(
 p,
 RuntimeSettingsService.parseModelList(p.getModels()),
 maskKey(p.getApiKey()));
 }

 /**
 * 密钥脱敏：只留首 4 位与末 4 位。
 *
 * <p>长度不足 8 位时整个打码（{@code ****}）—— 这种短密钥留头留尾
 * 就基本等于泄露全文了。早期设计 {@code _mask_key} 是同样的规则。
 *
 * <p>注意这与 里的 {@code _mask} 规则<b>不同</b>
 * （那个是留 6 位 + 末 4 位），两处各自照抄原文，没有统一 ——
 * 统一会改变接口返回的字符串，属于无必要的契约变更。
 */
 private static String maskKey(String key) {
 if (key == null || key.isEmpty()) {
 return "";
 }
 if (key.length() <= 8) {
 return "****";
 }
 return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
 }
}
