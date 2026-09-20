package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.dto.NoteDtos;
import com.ipas.assistant.entity.Note;
import com.ipas.assistant.repository.NoteRepository;
import com.ipas.assistant.service.llm.ChatModelFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 笔记业务逻辑。对应 。
 */
@Service
public class NoteService {

 private final NoteRepository noteRepository;
 private final ChatModelFactory chatModelFactory;
 private final RuntimeSettingsService settingsService;
 private final AppProperties properties;

 public NoteService(NoteRepository noteRepository,
 ChatModelFactory chatModelFactory,
 RuntimeSettingsService settingsService,
 AppProperties properties) {
 this.noteRepository = noteRepository;
 this.chatModelFactory = chatModelFactory;
 this.settingsService = settingsService;
 this.properties = properties;
 }

 /** 列表：按归档日期倒序，同一天内按最后修改时间倒序。 */
 @Transactional(readOnly = true)
 public List<NoteDtos.Out> list(Long userId) {
 return noteRepository.findByUserIdOrderByDayDescUpdatedAtDesc(userId)
 .stream()
 .map(NoteDtos.Out::from)
 .toList();
 }

 /**
 * 新建笔记。
 *
 * <p>{@code day} 缺省取<b>服务器本地日期</b>（原 {@code date.today()}）。
 * 这里刻意用 {@code LocalDate.now()}（本地）而不是 UTC 日期 ——
 * day 是给人看的"自然日"标签，「今天写的笔记归到今天」才是用户预期。
 * 若用 UTC，在东八区晚上 8 点之后写的笔记会被归到"明天"。
 *
 * <p>注意这与 createdAt（UTC 墙上时间）的语义不同，是早期设计的既有设计。
 */
 @Transactional
 public NoteDtos.Out create(Long userId, NoteDtos.Create payload) {
 Note note = new Note();
 note.setUserId(userId);
 note.setTitle(payload.title() == null ? "" : payload.title().strip());
 note.setContent(payload.content() == null ? "" : payload.content());
 note.setDay((payload.day() == null || payload.day().isBlank())
 ? LocalDate.now().toString()
 : payload.day().strip());
 return NoteDtos.Out.from(noteRepository.save(note));
 }

 /**
 * 更新笔记。
 *
 * <p>三个字段的判空逻辑各不相同，都是照抄原文：
 * <ul>
 * <li>{@code title}：非 null 就更新（<b>允许改成空串</b>，用户可以清空标题）；</li>
 * <li>{@code content}：非 null 就更新（允许清空正文）；</li>
 * <li>{@code day}：非 null <b>且 trim 后非空</b>才更新 ——
 * 因为 day 是归档键，不能是空串，否则这条笔记会从所有日期分组里消失。</li>
 * </ul>
 */
 @Transactional
 public NoteDtos.Out update(Long userId, Long noteId, NoteDtos.Update payload) {
 Note note = requireOwned(userId, noteId);

 if (payload.title() != null) {
 note.setTitle(payload.title().strip());
 }
 if (payload.content() != null) {
 note.setContent(payload.content());
 }
 if (payload.day() != null && !payload.day().isBlank()) {
 note.setDay(payload.day().strip());
 }

 // 先 flush 再构造响应：updatedAt 由 @PreUpdate 维护，刷写默认发生在事务提交时。
 // 不 flush 会返回旧时间戳，而笔记列表按 (day desc, updated_at desc) 排序 ——
 // 刚改过的笔记不会排到前面，用户会以为没保存成功。
 noteRepository.flush();

 return NoteDtos.Out.from(note);
 }

 /** 删除笔记。 */
 @Transactional
 public void delete(Long userId, Long noteId) {
 noteRepository.delete(requireOwned(userId, noteId));
 }

 /**
 * 取某天的全部笔记，按创建时间正序。供 AI 归纳使用（第三阶段）。
 *
 * <p>这里先把数据准备逻辑做出来，第三阶段只需在它后面接上模型调用，
 * 不必再改查询。
 */
 @Transactional(readOnly = true)
 public List<Note> listNotesOfDay(Long userId, String day) {
 return noteRepository.findByUserIdAndDayOrderByCreatedAtAsc(userId, day);
 }

 /**
 * AI 归纳某天的笔记。对应 {@code summarize_notes}。
 *
 * <p>流程与原文一致：取当天笔记 → 拼成带项目符号的文本 →
 * 让模型浓缩成 3~6 条要点 → 返回 {@code {day, summary, count}}。
 *
 * <p>「这一天还没有笔记」这个 400 校验放在最前面，因为它是<b>业务规则</b>，
 * 与 AI 是否就绪无关 —— 先校验数据，再谈能力。
 *
 * <p><b>为什么这里传 {@code enableThinking = null}</b>：
 * 深度思考参数是百炼的专有参数，且<b>只对流式调用生效</b>；
 * 非流式调用带上它会被平台直接拒绝（早期设计注释专门强调过）。
 * 这里是一次性的短请求，本来就不需要思考。
 */
 @Transactional(readOnly = true)
 public NoteDtos.SummarizeResponse summarize(Long userId, NoteDtos.SummarizeRequest payload) {
 List<Note> notes = listNotesOfDay(userId, payload.day());
 if (notes.isEmpty()) {
 throw ApiException.badRequest("这一天还没有笔记");
 }

 // 拼装素材：与原文的 f"- {title}：{content}".strip() 逐字一致
 String joined = notes.stream()
 .map(n -> ("- " + n.getTitle() + "：" + n.getContent()).strip())
 .collect(Collectors.joining("\n\n"));

 String prompt = "请把下面这些《" + payload.day() + "》的笔记归纳成 3~6 条要点，"
 + "用简洁的中文，可以用 Markdown 列表：\n\n" + joined;

 var settings = settingsService.load(userId);
 String provider = (payload.provider() == null || payload.provider().isBlank())
 ? properties.llm().provider()
 : payload.provider();
 // 模型缺省值按分支取：本地由工厂回落默认模型；云端则用设置里配的默认模型
 String model = payload.model();
 if ("cloud".equalsIgnoreCase(provider) && (model == null || model.isBlank())) {
 model = settings.model();
 }

 // ⚠️ 构造模型【不能】包进下面的 try：云端未配置时工厂会抛 400 业务异常，
 // 那是"客户端配置缺失"，必须原样透出；若被统一包装成 500，
 // 就退化成"服务端故障"，既误导排查，也丢掉了"去哪里配"的提示。
 ChatModel llm = chatModelFactory.create(provider, model,
 settings.apiKey(), settings.baseUrl(), null, null, settings);

 String text;
 try {
 text = llm.call(prompt);
 } catch (Exception e) {
 // 与原文一致：模型调用失败统一转成 500，并把原因带给用户
 // （不说原因的话，用户只能反复重试）
 throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
 "AI 归纳失败：" + e.getMessage());
 }

 return new NoteDtos.SummarizeResponse(payload.day(),
 text == null ? "" : text, notes.size());
 }

 private Note requireOwned(Long userId, Long noteId) {
 return noteRepository.findByIdAndUserId(noteId, userId)
 .orElseThrow(() -> ApiException.notFound("笔记不存在"));
 }
}
