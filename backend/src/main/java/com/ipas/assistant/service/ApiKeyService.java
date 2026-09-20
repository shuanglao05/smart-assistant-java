package com.ipas.assistant.service;

import com.ipas.assistant.dto.ApiKeyDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 「API 管理」面板的数据。对应 。
 *
 * <p>早期设计这个模块的注释写得很坦率：它只做「展示 + 跳转到厂商用量页 + 修改入口」，
 * 修改入口走 {@code /api/llm-config}。本 本实现保持同样的边界 ——
 * 本类只有一个只读方法。
 *
 * <p>它真正有价值的部分是 {@link #providerInfo}：把 base_url 映射成
 * 「人类能看懂的平台名 + 该平台的用量监控页地址」。用户想查"我这个月花了多少钱"时，
 * 直接点链接就能到对的地方，不用自己找控制台入口。
 */
@Service
public class ApiKeyService {

 private final RuntimeSettingsService settingsService;

 public ApiKeyService(RuntimeSettingsService settingsService) {
 this.settingsService = settingsService;
 }

 /** 当前云端配置（Key 脱敏）。 */
 @Transactional(readOnly = true)
 public ApiKeyDtos.ApiKeyInfo getInfo(Long userId) {
 RuntimeSettingsService.LlmSettings s = settingsService.load(userId);
 String baseUrl = s.baseUrl();
 String apiKey = s.apiKey() == null ? "" : s.apiKey();
 String[] info = providerInfo(baseUrl);

 return new ApiKeyDtos.ApiKeyInfo(
 info[0],
 baseUrl,
 s.model(),
 apiKey.isEmpty() ? "" : mask(apiKey),
 info[1],
 !apiKey.isBlank() && !baseUrl.isBlank());
 }

 /**
 * 密钥脱敏：留前 6 位与末 4 位。
 *
 * <p>⚠️ 这里的规则与 {@code LlmProviderService.maskKey}（4 + 4）<b>不同</b>，
 * 是<b>刻意如此</b>：两处分别照抄早期设计 {@code api_keys._mask} 与
 * {@code llm_providers._mask_key} 的实现，它们本来就不一致。
 *
 * <p>统一成一个规则会更"整洁"，但会改变其中一个接口返回的字符串内容 ——
 * 对于"前端只是把掩码显示出来"的场景，这种改动没有任何收益，
 * 却多了一处需要验证的契约变化。所以在迁移期间保持原样。
 */
 private static String mask(String key) {
 if (key == null || key.isEmpty()) {
 return "";
 }
 if (key.length() <= 10) {
 return key.substring(0, Math.min(3, key.length()))
 + "..."
 + key.substring(Math.max(0, key.length() - 2));
 }
 return key.substring(0, 6) + "..." + key.substring(key.length() - 4);
 }

 /**
 * 按 base_url 推断厂商名与用量监控页地址。
 *
 * <p>匹配顺序<b>有讲究</b>：必须把更具体的域名排在前面。
 * 例如 {@code open.bigmodel.cn} 同时含 "bigmodel" 和 "zhipu"，
 * 而 {@code api.openai.com} 含 "openai" —— 若把 {@code openai} 的判断提前，
 * 智谱的域名不会误匹配（它不含 "openai"），但像
 * {@code anthropic.com} 与 {@code google} 这类就得逐个核对，避免交叉命中。
 *
 * <p>兜底返回「自定义/其他」，用量页回落到 base_url 本身或 OpenAI 地址 ——
 * 早期设计的兜底也是这样，让链接至少能点、不至于报错。
 *
 * @return {@code [厂商显示名, 用量页 URL]}
 */
 private static String[] providerInfo(String baseUrl) {
 String u = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);

 if (u.contains("dashscope") || u.contains("aliyun")) {
 return new String[]{"阿里云百炼 (DashScope)", "https://dashscope.console.aliyun.com/"};
 }
 if (u.contains("deepseek")) {
 return new String[]{"DeepSeek", "https://platform.deepseek.com/usage"};
 }
 if (u.contains("bigmodel") || u.contains("zhipu")) {
 return new String[]{"智谱 (BigModel)", "https://bigmodel.cn/console/account"};
 }
 if (u.contains("moonshot") || u.contains("kimi")) {
 return new String[]{"月之暗面 (Moonshot/Kimi)",
 "https://platform.moonshot.cn/console/personal"};
 }
 if (u.contains("siliconflow")) {
 return new String[]{"硅基流动 (SiliconFlow)",
 "https://cloud.siliconflow.cn/account/ak"};
 }
 if (u.contains("openai")) {
 return new String[]{"OpenAI", "https://platform.openai.com/usage"};
 }
 if (u.contains("anthropic")) {
 return new String[]{"Anthropic Claude",
 "https://console.anthropic.com/settings/billing"};
 }
 if (u.contains("google") || u.contains("gemini")) {
 return new String[]{"Google Gemini", "https://aistudio.google.com/app/apikey"};
 }
 return new String[]{"自定义/其他",
 (baseUrl == null || baseUrl.isBlank()) ? "https://platform.openai.com/" : baseUrl};
 }
}
