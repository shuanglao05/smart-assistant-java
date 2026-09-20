package com.ipas.assistant;

import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.entity.Course;
import com.ipas.assistant.entity.Conversation;
import com.ipas.assistant.entity.FileItem;
import com.ipas.assistant.entity.KbCollection;
import com.ipas.assistant.entity.Message;
import com.ipas.assistant.entity.Skill;
import com.ipas.assistant.entity.TodoItem;
import com.ipas.assistant.entity.User;
import com.ipas.assistant.repository.AppSettingRepository;
import com.ipas.assistant.repository.ConversationRepository;
import com.ipas.assistant.repository.CourseRepository;
import com.ipas.assistant.repository.FileItemRepository;
import com.ipas.assistant.repository.KbChunkRepository;
import com.ipas.assistant.repository.KbCollectionRepository;
import com.ipas.assistant.repository.LlmProviderRepository;
import com.ipas.assistant.repository.MessageRepository;
import com.ipas.assistant.repository.NoteRepository;
import com.ipas.assistant.repository.NotificationRepository;
import com.ipas.assistant.repository.ScheduleRepository;
import com.ipas.assistant.repository.SkillRepository;
import com.ipas.assistant.repository.TodoRepository;
import com.ipas.assistant.repository.UserRepository;
import com.ipas.assistant.security.JwtService;
import com.ipas.assistant.security.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import com.ipas.assistant.service.LlmConnectivityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口契约测试（第一阶段 + 第二阶段）。
 *
 * <h2>这个测试在验证什么，为什么必须写</h2>
 *
 * <p>本次迁移的<b>最大风险不是代码能不能编过，而是接口契约有没有走样</b>。
 * 前端 React 代码是原封不动复用的，它认死了这些东西：
 * <ul>
 * <li>JSON 字段是 snake_case（{@code token_type} / {@code font_size} / {@code start_at}）；</li>
 * <li>错误响应体是 {@code {"detail": "中文提示"}}；</li>
 * <li>未登录是 <b>401</b>（前端拦截器靠它清 token 并跳回登录页）；</li>
 * <li>业务校验失败是 <b>400</b>、框架参数校验失败是 <b>422</b>。</li>
 * </ul>
 *
 * <p>任何一条走样，前端就会表现成「提示 undefined」「登录后不跳转」「点保存没反应」
 * 这类问题，而且很难定位到根因。所以这些断言必须自动化固化下来，
 * 每一个 {@code jsonPath} 都是一条契约条款。
 *
 * <h2>测试策略：不依赖 MySQL</h2>
 *
 * <p>用 {@code @MockitoBean} 替换掉所有仓储，并排除数据源自动配置
 * （见 {@code application-test.yml}）。这样测试能在任何机器上跑，
 * 同时<b>依然完整覆盖</b>：Spring Security 过滤器链 → Controller → Service
 * → Jackson 序列化。被替换掉的只有最外层的数据存取，而那些 SQL 逻辑
 * 由针对真实 MySQL 的端到端验证来覆盖。
 *
 * <p><b>注意：每新增一个 Controller/Service，就必须在这里补上它依赖的仓储替身。</b>
 * 否则 Spring 容器会因为找不到 bean 而启动失败，报错是
 * {@code NoSuchBeanDefinitionException}（看起来跟"测试失败"无关，容易懵）。
 * 第二阶段新增 6 个模块后，本类顶部一共 mock 了 8 个仓储。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractTest {

 @Autowired
 private MockMvc mockMvc;

 @Autowired
 private JwtService jwtService;

 @Autowired
 private PasswordService passwordService;

 /** 读取「数据目录」等启动配置：节假日用例要靠它算出缓存文件的真实落点。 */
 @Autowired
 private AppProperties appProperties;

 // ---- 仓储替身（13 个，覆盖当前所有模块的依赖）----
 //
 // ⚠️ 每新增一个 Controller，就必须在这里补上它（以及它调用的 Service）
 // 所依赖的仓储替身。否则 Spring 容器会因为找不到 bean 而启动失败，
 // 报错是 NoSuchBeanDefinitionException —— 看起来跟"测试失败"无关，容易懵。
 @MockitoBean
 private UserRepository userRepository;
 @MockitoBean
 private TodoRepository todoRepository;
 @MockitoBean
 private NoteRepository noteRepository;
 @MockitoBean
 private SkillRepository skillRepository;
 @MockitoBean
 private ConversationRepository conversationRepository;
 @MockitoBean
 private NotificationRepository notificationRepository;
 @MockitoBean
 private ScheduleRepository scheduleRepository;
 @MockitoBean
 private CourseRepository courseRepository;
 @MockitoBean
 private AppSettingRepository appSettingRepository;
 @MockitoBean
 private LlmProviderRepository llmProviderRepository;
 @MockitoBean
 private FileItemRepository fileItemRepository;
 @MockitoBean
 private MessageRepository messageRepository;
 // ---- 第四阶段 RAG 引入的仓储：RagService 依赖下面两个做向量检索，
 // 不 mock 会导致 Spring 容器启动失败（NoSuchBeanDefinitionException），
 // 从而让全部 49 个契约测试一起 Error。与 files/kb 模块共用 fileItemRepository。 ----
 @MockitoBean
 private KbChunkRepository kbChunkRepository;
 @MockitoBean
 private KbCollectionRepository kbCollectionRepository;

 /**
 * Ollama 连通性服务的替身。
 *
 * <p><b>为什么要 mock 它</b>：`/api/llm-options` 会真的去连本机 Ollama（`/api/tags`）。
 * 契约测试的原则是「零外部依赖、结果确定」—— 若不 mock，测试结果会取决于
 * <b>运行环境</b>：本机 Ollama 在线时会返回真实模型清单（用例断言失败），
 * 不在线时才走"回落"分支（断言通过）。这就是一个典型的"环境相关"脆弱用例。
 *
 * <p>改成替身后，我们显式地让 {@code listOllamaChatModels(...)} 返回空列表，
 * 稳定地走「回落为配置里的单个本地模型」这条分支，与"Ollama 不可达"等价。
 * 该服务只被 `/api/llm-options` 与 `/api/llm-config/local-models` 使用，后者测试未覆盖，
 * 故加这个替身不会影响其它用例。
 */
 @MockitoBean
 private LlmConnectivityService llmConnectivityService;

 /** 测试用户：登录成功分支要用到它的真实 bcrypt 哈希。 */
 private User existingUser;

 @BeforeEach
 void setUp() {
 existingUser = new User();
 existingUser.setId(1L);
 existingUser.setUsername("alice");
 existingUser.setHashedPassword(passwordService.hash("pw123456"));
 existingUser.setLanguage("zh");
 existingUser.setFontSize("medium");
 existingUser.setTheme("dark");

 when(userRepository.findByUsername("alice")).thenReturn(Optional.of(existingUser));
 when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
 when(userRepository.findByUsername("brand-new-user")).thenReturn(Optional.empty());
 when(userRepository.existsByUsername("brand-new-user")).thenReturn(false);
 when(userRepository.existsByUsername("alice")).thenReturn(true);
 when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
 User u = invocation.getArgument(0);
 u.setId(42L);
 return u;
 });

 // 运行时可改配置默认返回"库里什么都没有" —— 于是所有配置项都会回落到
 // application.yml 的默认值。这正是新用户首次打开的形态，
 // 也让下面的用例可以确定性地断言默认值。
 when(appSettingRepository.findByUserIdAndSettingKeyIn(anyLong(), any()))
 .thenReturn(List.of());
 when(appSettingRepository.findByUserIdAndSettingKey(anyLong(), anyString()))
 .thenReturn(Optional.empty());
 when(llmProviderRepository.findByUserIdOrderByIdAsc(anyLong())).thenReturn(List.of());
 }

 private String token() {
 return jwtService.createAccessToken(1L, "alice");
 }

 // ======================================================================
 // 一、鉴权与基础契约（第一阶段）
 // ======================================================================

 @Test
 @DisplayName("GET /api/health —— 免登录访问，字段为 snake_case，与原 接口框架 输出一致")
 void healthIsPublicAndUsesSnakeCase() throws Exception {
 mockMvc.perform(get("/api/health"))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.status").value("ok"))
 .andExpect(jsonPath("$.version").isNotEmpty())
 .andExpect(jsonPath("$.llm_provider").isNotEmpty())
 .andExpect(jsonPath("$.llm_model").isNotEmpty());
 }

 @Test
 @DisplayName("POST /api/auth/register —— 注册成功返回 token / token_type / username")
 void registerReturnsTokenShape() throws Exception {
 mockMvc.perform(post("/api/auth/register")
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"username":"brand-new-user","password":"pw123456"}
 """))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.token").isNotEmpty())
 .andExpect(jsonPath("$.token_type").value("bearer"))
 .andExpect(jsonPath("$.username").value("brand-new-user"));
 }

 @Test
 @DisplayName("POST /api/auth/register —— 纯空格用户名返回 400「用户名不能为空」")
 void registerRejectsWhitespaceOnlyUsername() throws Exception {
 // 这是最容易做错的一条：若用 @NotBlank 做校验，这里会返回 422 而不是 400。
 // 早期设计 数据校验框架 的 min_length=1 挡不住 " "（长度是 3），
 // 所以必须由业务层的 strip() 判断给出 400。详见 RegisterRequest 注释。
 mockMvc.perform(post("/api/auth/register")
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"username":" ","password":"pw123456"}
 """))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail").value("用户名不能为空"));
 }

 @Test
 @DisplayName("POST /api/auth/register —— 用户名已存在返回 400，且提示文案与原文一致")
 void registerRejectsDuplicateUsername() throws Exception {
 mockMvc.perform(post("/api/auth/register")
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"username":"alice","password":"pw123456"}
 """))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail").value("用户名已存在"));
 }

 @Test
 @DisplayName("POST /api/auth/register —— 缺少 username 字段返回 422（框架参数校验）")
 void registerWithoutUsernameIsUnprocessable() throws Exception {
 mockMvc.perform(post("/api/auth/register")
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"password":"pw123456"}
 """))
 .andExpect(status().isUnprocessableEntity())
 .andExpect(jsonPath("$.detail").isNotEmpty());
 }

 @Test
 @DisplayName("POST /api/auth/login —— 密码正确返回 token")
 void loginSucceedsWithCorrectPassword() throws Exception {
 mockMvc.perform(post("/api/auth/login")
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"username":"alice","password":"pw123456"}
 """))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.token").isNotEmpty())
 .andExpect(jsonPath("$.token_type").value("bearer"))
 .andExpect(jsonPath("$.username").value("alice"));
 }

 @Test
 @DisplayName("POST /api/auth/login —— 密码错误与账号不存在返回完全相同的 401")
 void loginDoesNotRevealWhetherAccountExists() throws Exception {
 mockMvc.perform(post("/api/auth/login")
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"username":"alice","password":"wrong-password"}
 """))
 .andExpect(status().isUnauthorized())
 .andExpect(jsonPath("$.detail").value("用户名或密码错误"));

 // 账号不存在时提示必须一字不差，否则可被用来枚举有效账号
 mockMvc.perform(post("/api/auth/login")
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"username":"no-such-user","password":"whatever"}
 """))
 .andExpect(status().isUnauthorized())
 .andExpect(jsonPath("$.detail").value("用户名或密码错误"));
 }

 @Test
 @DisplayName("GET /api/users/me —— 不带 token 返回 401 + WWW-Authenticate: Bearer")
 void protectedEndpointRequiresToken() throws Exception {
 mockMvc.perform(get("/api/users/me"))
 .andExpect(status().isUnauthorized())
 .andExpect(jsonPath("$.detail").value("认证失败"))
 .andExpect(header().string("WWW-Authenticate", "Bearer"));
 }

 @Test
 @DisplayName("GET /api/users/me —— 无效 token 同样返回 401")
 void protectedEndpointRejectsInvalidToken() throws Exception {
 mockMvc.perform(get("/api/users/me")
 .header("Authorization", "Bearer not-a-real-token"))
 .andExpect(status().isUnauthorized())
 .andExpect(jsonPath("$.detail").value("认证失败"));
 }

 @Test
 @DisplayName("GET /api/users/me —— 带有效 token 返回资料，且绝不泄露密码哈希")
 void getProfileWithValidToken() throws Exception {
 mockMvc.perform(get("/api/users/me")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.id").value(1))
 .andExpect(jsonPath("$.username").value("alice"))
 .andExpect(jsonPath("$.font_size").value("medium"))
 .andExpect(jsonPath("$.hashed_password").doesNotExist())
 .andExpect(jsonPath("$.hashedPassword").doesNotExist());
 }

 @Test
 @DisplayName("PATCH /api/users/me —— 非法主题返回 400，中文提示与原文一致")
 void updateProfileRejectsInvalidTheme() throws Exception {
 mockMvc.perform(patch("/api/users/me")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"theme":"rainbow"}
 """))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail").value("不支持的主题: rainbow"));
 }

 @Test
 @DisplayName("PATCH /api/users/me —— 改密时缺少 current_password 返回 400")
 void updateProfileRequiresCurrentPassword() throws Exception {
 mockMvc.perform(patch("/api/users/me")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"new_password":"brand-new-pw"}
 """))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail").value("修改密码需要提供 current_password"));
 }

 @Test
 @DisplayName("未知路径返回 404 且响应体为 接口框架 风格 {\"detail\":\"Not Found\"}")
 void unknownPathReturnsFastApiStyle404() throws Exception {
 mockMvc.perform(get("/api/no-such-endpoint")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isNotFound())
 .andExpect(jsonPath("$.detail").value("Not Found"));
 }

 @Test
 @DisplayName("口令哈希使用 $2b$ 前缀与 12 轮强度，与早期实现 bcrypt 输出格式一致")
 void passwordHashMatchesPythonFormat() {
 String hash = passwordService.hash("some-password");
 org.junit.jupiter.api.Assertions.assertTrue(
 hash.startsWith("$2b$12$"),
 "哈希前缀应为 $2b$12$，实际为: " + hash);
 org.junit.jupiter.api.Assertions.assertTrue(passwordService.verify("some-password", hash));
 org.junit.jupiter.api.Assertions.assertFalse(passwordService.verify("wrong", hash));
 org.junit.jupiter.api.Assertions.assertFalse(
 passwordService.verify("some-password", "not-a-bcrypt-hash"));
 }

 // ======================================================================
 // 二、第二阶段模块的行为契约
 // ======================================================================

 @Test
 @DisplayName("POST /api/todos —— task 会被 trim（前端容易多敲空格）")
 void createTodoTrimsTask() throws Exception {
 when(todoRepository.save(any(TodoItem.class))).thenAnswer(inv -> {
 TodoItem t = inv.getArgument(0);
 t.setId(7L);
 return t;
 });

 mockMvc.perform(post("/api/todos")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"task":" 写报告 "}
 """))
 .andExpect(status().isOk())
 // 若没 trim，前端列表里会出现带前导空格的内容，且排序/去重都会受影响
 .andExpect(jsonPath("$.task").value("写报告"));
 }

 @Test
 @DisplayName("POST /api/todos —— 空 task 返回 422（@NotBlank 拦住）")
 void createTodoRejectsBlankTask() throws Exception {
 mockMvc.perform(post("/api/todos")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"task":""}
 """))
 .andExpect(status().isUnprocessableEntity())
 .andExpect(jsonPath("$.detail").isNotEmpty());
 }

 @Test
 @DisplayName("GET /api/todos/stats —— 返回 total / done / updated_at 三个字段")
 void todoStatsShape() throws Exception {
 when(todoRepository.countByUserId(anyLong())).thenReturn(5L);
 when(todoRepository.countByUserIdAndDoneTrue(anyLong())).thenReturn(2L);

 mockMvc.perform(get("/api/todos/stats")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.total").value(5))
 .andExpect(jsonPath("$.done").value(2))
 .andExpect(jsonPath("$.updated_at").isNotEmpty());
 }

 @Test
 @DisplayName("DELETE /api/todos/{id} —— 返回 {\"status\":\"deleted\"} 而不是 204")
 void deleteTodoReturnsStatusBody() throws Exception {
 // 前端读 res.data.status；若改成 204 No Content，这里会拿到 undefined
 TodoItem owned = new TodoItem();
 owned.setId(9L);
 owned.setUserId(1L);
 when(todoRepository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(owned));

 mockMvc.perform(delete("/api/todos/9")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.status").value("deleted"));
 }

 @Test
 @DisplayName("PATCH /api/todos/{id} —— 别人的待办返回 404 而不是 403（不泄露存在性）")
 void updateOthersTodoReturns404() throws Exception {
 // 仓储按 (id, userId) 查，别人的记录查不到 → 统一按"不存在"处理。
 // 若返回 403，攻击者就能通过状态码差异判断出"这个 id 是存在的"。
 when(todoRepository.findByIdAndUserId(999L, 1L)).thenReturn(Optional.empty());

 mockMvc.perform(patch("/api/todos/999")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"done":true}
 """))
 .andExpect(status().isNotFound())
 .andExpect(jsonPath("$.detail").value("待办不存在"));
 }

 @Test
 @DisplayName("POST /api/courses —— 节次倒挂会被自动交换（4,2 → 2,4），不报错")
 void createCourseNormalizesSectionOrder() throws Exception {
 when(courseRepository.save(any(Course.class))).thenAnswer(inv -> {
 Course c = inv.getArgument(0);
 c.setId(1L);
 return c;
 });

 mockMvc.perform(post("/api/courses")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"name":"编译原理","weekday":3,"start_section":4,"end_section":2}
 """))
 .andExpect(status().isOk())
 // 用户在课表网格上常先点结束节次，直接报错会让人莫名其妙
 .andExpect(jsonPath("$.start_section").value(2))
 .andExpect(jsonPath("$.end_section").value(4));
 }

 @Test
 @DisplayName("POST /api/courses —— weekday 越界返回 422")
 void createCourseRejectsInvalidWeekday() throws Exception {
 mockMvc.perform(post("/api/courses")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"name":"高数","weekday":9,"start_section":1,"end_section":2}
 """))
 .andExpect(status().isUnprocessableEntity())
 .andExpect(jsonPath("$.detail").isNotEmpty());
 }

 @Test
 @DisplayName("DELETE /api/skills/{id} —— 同时把它从所有会话的 active_skill_ids 中摘掉")
 void deleteSkillAlsoCleansSessions() throws Exception {
 Skill skill = new Skill();
 skill.setId(5L);
 skill.setUserId(1L);
 skill.setName("表格回答");
 skill.setPrompt("一律用表格");
 when(skillRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(skill));

 Conversation conv = new Conversation();
 conv.setId(100L);
 conv.setUserId(1L);
 conv.setActiveSkillIds(new ArrayList<>(List.of(5L, 8L)));
 when(conversationRepository.findAllByUserId(1L)).thenReturn(List.of(conv));

 mockMvc.perform(delete("/api/skills/5")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.status").value("deleted"));

 // 关键断言：悬空 id 必须被摘掉，否则第三阶段会出现"勾了技能却不生效"
 org.junit.jupiter.api.Assertions.assertEquals(
 List.of(8L), conv.getActiveSkillIds(),
 "删除技能后，会话里的 active_skill_ids 应当只去掉该技能 id");
 }

 @Test
 @DisplayName("GET /api/notifications —— limit 越界时静默夹到 200，不报错")
 void notificationListClampsLimit() throws Exception {
 when(notificationRepository.findByUserIdOrderByCreatedAtDesc(anyLong(), any()))
 .thenReturn(List.of());

 mockMvc.perform(get("/api/notifications")
 .header("Authorization", "Bearer " + token())
 .param("limit", "999999"))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$").isArray());
 }

 @Test
 @DisplayName("PATCH /api/notifications/{id} —— is_read 是查询参数且默认 true")
 void markNotificationReadDefaultsToTrue() throws Exception {
 com.ipas.assistant.entity.Notification n = new com.ipas.assistant.entity.Notification();
 n.setId(3L);
 n.setUserId(1L);
 n.setTitle("t");
 n.setIsRead(false);
 when(notificationRepository.findByIdAndUserId(3L, 1L)).thenReturn(Optional.of(n));

 // 不带 is_read 参数（前端就是这么调的）应当表示"标记为已读"
 mockMvc.perform(patch("/api/notifications/3")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.is_read").value(true));
 }

 @Test
 @DisplayName("POST /api/schedules —— start_at 以常规十进制输出，不是科学计数法")
 void scheduleStartAtIsPlainNumber() throws Exception {
 com.ipas.assistant.entity.Schedule s = new com.ipas.assistant.entity.Schedule();
 s.setId(1L);
 s.setUserId(1L);
 s.setTitle("组会");
 s.setStartAt(1789600000.0);
 when(scheduleRepository.save(any(com.ipas.assistant.entity.Schedule.class)))
 .thenReturn(s);

 mockMvc.perform(post("/api/schedules")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"title":"组会","start_at":1789600000.0}
 """))
 .andExpect(status().isOk())
 // 默认的 Double 序列化会输出 1.7896E9（合法但与早期设计不一致，
 // 见 JacksonConfig 的注释）。这里固化"必须是普通十进制"这条契约。
 .andExpect(jsonPath("$.start_at").value(1789600000.0));
 }

 @Test
 @DisplayName("POST /api/notes/summarize —— 当天没笔记返回 400（业务校验先于 AI 可用性）")
 void summarizeNotesValidatesBeforeAi() throws Exception {
 when(noteRepository.findByUserIdAndDayOrderByCreatedAtAsc(anyLong(), any()))
 .thenReturn(List.of());

 mockMvc.perform(post("/api/notes/summarize")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"day":"2020-01-01"}
 """))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail").value("这一天还没有笔记"));
 }

 @Test
 @DisplayName("POST /api/notes/summarize —— 有笔记但云端未配置时返回 400（不再返回 501）")
 void summarizeNotesFailsFastWhenCloudNotConfigured() throws Exception {
 var note = new com.ipas.assistant.entity.Note();
 note.setId(1L);
 note.setDay("2026-09-16");
 note.setTitle("t");
 note.setContent("c");
 when(noteRepository.findByUserIdAndDayOrderByCreatedAtAsc(anyLong(), any()))
 .thenReturn(List.of(note));

 // 501 已移除：现在会真的去构造模型。这里刻意指定 provider=cloud ——
 // 测试环境没配云端凭据，工厂会【立刻】抛 400 而不会发起任何网络请求，
 // 因此用例是确定性的（若用默认的 ollama，本机是否开着 Ollama 会让结果不稳定）。
 mockMvc.perform(post("/api/notes/summarize")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"day":"2026-09-16","provider":"cloud"}
 """))
 .andExpect(status().isBadRequest())
 // 文案取自 ChatModelFactory（与"会话切到云端"处的提示不是同一处，
 // 那边是 SessionService 自己的措辞，因此这里不能用它的字符串）
 .andExpect(jsonPath("$.detail")
 .value("云端模型未配置：请在设置里接入 API，或配置默认云端凭据"));
 }

 @Test
 @DisplayName("POST /api/courses/import —— 图片分支缺云端凭据时返回 400（不发请求、不超时）")
 void courseImportImageRequiresVisionModel() throws Exception {
 // 图片识别必须走云端视觉模型。未配置时应当【立刻】400，
 // 而不是让请求发出去干等超时 —— 这是早期设计明确要求的分支顺序。
 mockMvc.perform(post("/api/courses/import")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"image":"data:image/jpeg;base64,AAAA"}
 """))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail")
 .value("图片识别需要云端视觉模型，请在设置里接入 API 或配置云端凭据"));
 }

 @Test
 @DisplayName("POST /api/courses/import —— 三种输入都没给时返回 400")
 void courseImportRequiresInput() throws Exception {
 mockMvc.perform(post("/api/courses/import")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("{}"))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail").value("请提供课表网址，或直接粘贴课表内容"));
 }

 // ======================================================================
 // 三、LLM 配置落库（替代早期设计的 .env 回写）
 // ======================================================================

 @Test
 @DisplayName("GET /api/llm-config —— 库里没有配置项时，全部回落到 application.yml 默认值")
 void llmConfigFallsBackToDefaults() throws Exception {
 // 这是「配置落库」方案的核心行为：配置表为空时系统照常工作，
 // 与"只用 application.yml 启动"完全一致（早期设计 .env 没写 CLOUD_* 时也是这个效果）
 mockMvc.perform(get("/api/llm-config")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.cloud_base_url").value(""))
 .andExpect(jsonPath("$.cloud_model").value(""))
 .andExpect(jsonPath("$.cloud_models").isArray())
 .andExpect(jsonPath("$.enable_thinking").value(false))
 .andExpect(jsonPath("$.thinking_budget").value(0))
 // 代理相关三字段必须都在（前端设置页要用它们展示"环境变量里是什么、实际用哪个"）
 .andExpect(jsonPath("$.trust_env").value(false))
 .andExpect(jsonPath("$.proxy_url").value(""))
 .andExpect(jsonPath("$.env_proxy").exists())
 .andExpect(jsonPath("$.effective_proxy").exists());
 }

 @Test
 @DisplayName("POST /api/llm-config —— 没有 Key 时返回 400，且提示文案与原文一致")
 void llmConfigRejectsSaveWithoutKey() throws Exception {
 mockMvc.perform(post("/api/llm-config")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("{}"))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail")
 .value("API Key 不能为空（请先填写平台申请的 Key）"));
 }

 @Test
 @DisplayName("POST /api/llm-config/test —— 没有 Key 时恒返回 200 且 ok=false")
 void llmConfigTestAlwaysReturns200() throws Exception {
 // 前端把 message 直接显示在设置页提示条上，所以这个接口不能因为
 // "测试不通过"就返回 4xx —— 否则前端还要额外区分"请求错了"和"测试没过"
 mockMvc.perform(post("/api/llm-config/test")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("{}"))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.ok").value(false))
 .andExpect(jsonPath("$.message")
 .value("尚未填写 API Key，后端也没有已保存的 Key"))
 .andExpect(jsonPath("$.thinking_supported").value(false));
 }

 @Test
 @DisplayName("GET /api/llm-config/context-window —— 返回预设档位与允许范围")
 void contextWindowShape() throws Exception {
 mockMvc.perform(get("/api/llm-config/context-window")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 // 默认值来自 application.yml 的 app.llm.ollama.num-ctx
 .andExpect(jsonPath("$.ollama_num_ctx").value(8192))
 .andExpect(jsonPath("$.presets").isArray())
 .andExpect(jsonPath("$.min").value(512))
 .andExpect(jsonPath("$.max").value(131072))
 // 必须带说明：云端模型的上下文长度不可调，这条提示避免用户误解
 .andExpect(jsonPath("$.note").isNotEmpty());
 }

 @Test
 @DisplayName("POST /api/llm-config/context-window —— 超出范围返回 400")
 void contextWindowRejectsOutOfRange() throws Exception {
 mockMvc.perform(post("/api/llm-config/context-window")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"ollama_num_ctx":999999}
 """))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail").value("上下文窗口需在 512 ~ 131072 之间"));
 }

 @Test
 @DisplayName("GET /api/api-keys —— 未配置时 configured=false 且掩码为空")
 void apiKeysWhenNotConfigured() throws Exception {
 mockMvc.perform(get("/api/api-keys")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.configured").value(false))
 .andExpect(jsonPath("$.masked_key").value(""))
 // 兜底平台名与用量页地址（base_url 为空时回落到 OpenAI 用量页）
 .andExpect(jsonPath("$.provider").value("自定义/其他"))
 .andExpect(jsonPath("$.usage_url").isNotEmpty());
 }

 @Test
 @DisplayName("GET /api/llm-options —— Ollama 不可达时回落为配置里的单个本地模型")
 void llmOptionsFallsBackWhenOllamaUnavailable() throws Exception {
 // 显式模拟"Ollama 不可达 / 没有可用模型"：让连通性服务返回空列表。
 // 这样本用例不再依赖"运行环境到底有没有在跑 Ollama"—— 结果始终确定。
 when(llmConnectivityService.listOllamaChatModels(anyString(), any())).thenReturn(List.of());

 // 接口不能因为连不上 Ollama 而失败（前端打开模型下拉就会调它），
 // 而要至少回落到配置里的单个本地模型
 mockMvc.perform(get("/api/llm-options")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$").isArray())
 .andExpect(jsonPath("$[0].provider").value("ollama"))
 .andExpect(jsonPath("$[0].model").value("qwen3:8b"))
 // 本地项不带 provider_id —— 对应字典里"根本没有这个键"
 .andExpect(jsonPath("$[0].provider_id").doesNotExist())
 .andExpect(jsonPath("$[0].platform").value("本地"));
 }

 @Test
 @DisplayName("GET /api/llm-providers —— 未接入任何平台时返回空数组")
 void llmProvidersEmptyList() throws Exception {
 mockMvc.perform(get("/api/llm-providers")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$").isArray())
 .andExpect(jsonPath("$").isEmpty());
 }

 @Test
 @DisplayName("DELETE /api/llm-providers/{id} —— 不存在的接入返回 404")
 void deleteUnknownProviderReturns404() throws Exception {
 when(llmProviderRepository.findByIdAndUserId(999L, 1L)).thenReturn(Optional.empty());

 mockMvc.perform(delete("/api/llm-providers/999")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isNotFound())
 .andExpect(jsonPath("$.detail").value("未找到该接入"));
 }

 // ======================================================================
 // 四、会话与消息
 // ======================================================================

 @Test
 @DisplayName("POST /api/sessions —— 不带请求体也能建会话（前端点「新对话」就是这样）")
 void createSessionWithoutBody() throws Exception {
 // 原签名是 payload: SessionCreate | None = None。
 // 若后端把请求体设成必填，这个最常用的按钮会直接 400。
 when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> {
 Conversation c = inv.getArgument(0);
 c.setId(77L);
 return c;
 });

 mockMvc.perform(post("/api/sessions")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.id").value(77))
 // 默认标题是「新对话」—— chat 模块靠这个字符串判断要不要自动命名，
 // 所以它不只是个文案，而是流程标记
 .andExpect(jsonPath("$.title").value("新对话"))
 .andExpect(jsonPath("$.active_skill_ids").isArray())
 .andExpect(jsonPath("$.active_kb_ids").isArray());
 }

 @Test
 @DisplayName("GET /api/sessions/{id}/messages —— 别人的会话返回 404（不泄露存在性）")
 void messagesOfOthersSessionReturns404() throws Exception {
 when(conversationRepository.findByIdAndUserId(555L, 1L)).thenReturn(Optional.empty());

 mockMvc.perform(get("/api/sessions/555/messages")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isNotFound())
 .andExpect(jsonPath("$.detail").value("会话不存在"));
 }

 @Test
 @DisplayName("GET /api/sessions/{id}/messages —— ref_files 由 ref_file_ids 还原而来")
 void messagesResolveRefFiles() throws Exception {
 Conversation conv = new Conversation();
 conv.setId(10L);
 conv.setUserId(1L);
 when(conversationRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(conv));

 Message userMsg = new Message();
 userMsg.setId(1L);
 userMsg.setConversationId(10L);
 userMsg.setRole("user");
 userMsg.setContent("看看这个文档");
 // 数据库里存的是 id 数组的 JSON 文本
 userMsg.setRefFileIds("[3,7]");

 Message aiMsg = new Message();
 aiMsg.setId(2L);
 aiMsg.setConversationId(10L);
 aiMsg.setRole("assistant");
 aiMsg.setContent("好的");
 aiMsg.setProvider("cloud");
 aiMsg.setModel("glm-5.2");

 when(messageRepository.findByConversationIdOrderByIdAsc(10L))
 .thenReturn(List.of(userMsg, aiMsg));

 com.ipas.assistant.entity.FileItem f = new com.ipas.assistant.entity.FileItem();
 f.setId(3L);
 f.setFilename("规范.pdf");
 f.setSize(1024L);
 when(fileItemRepository.findByIdInAndUserId(any(), anyLong())).thenReturn(List.of(f));

 mockMvc.perform(get("/api/sessions/10/messages")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 // 用户消息带上了附件信息（前端据此刻画气泡上方的可点文档名）
 .andExpect(jsonPath("$[0].ref_files[0].id").value(3))
 .andExpect(jsonPath("$[0].ref_files[0].filename").value("规范.pdf"))
 // 助手消息带上了产生它的模型，且 ref_files 恒为空数组
 .andExpect(jsonPath("$[1].provider").value("cloud"))
 .andExpect(jsonPath("$[1].model").value("glm-5.2"))
 .andExpect(jsonPath("$[1].ref_files").isEmpty());
 }

 @Test
 @DisplayName("PATCH /api/sessions/{id} —— 只传 provider=cloud 但未配凭据时返回 400")
 void switchToCloudWithoutCredentialsIsRejected() throws Exception {
 Conversation conv = new Conversation();
 conv.setId(10L);
 conv.setUserId(1L);
 when(conversationRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(conv));

 // 该测试里 app_setting 全为空 → 云端未配置
 mockMvc.perform(patch("/api/sessions/10")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"provider":"cloud"}
 """))
 // 若不拦，会话会被切到一个用不了的模型上，用户下次提问才炸，
 // 而且错误出现在聊天窗口里，很难联想到是这里切换造成的
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail")
 .value("云端模型未配置：请在设置里接入 API 或配置云端凭据"));
 }

 @Test
 @DisplayName("PATCH /api/sessions/{id} —— 切成本地 Ollama 时按默认模型赋值")
 void switchToOllamaUsesDefaultModel() throws Exception {
 Conversation conv = new Conversation();
 conv.setId(10L);
 conv.setUserId(1L);
 conv.setProvider("cloud");
 conv.setModel("some-cloud-model");
 when(conversationRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(conv));

 mockMvc.perform(patch("/api/sessions/10")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"provider":"ollama"}
 """))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.provider").value("ollama"))
 // 模型回落到 application.yml 的 app.llm.ollama.model
 .andExpect(jsonPath("$.model").value("qwen3:8b"));
 }

 @Test
 @DisplayName("DELETE /api/sessions —— 清空返回 status=deleted 与 count")
 void deleteAllSessionsReturnsCount() throws Exception {
 when(conversationRepository.findAllByUserId(1L)).thenReturn(List.of());

 mockMvc.perform(delete("/api/sessions")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.status").value("deleted"))
 .andExpect(jsonPath("$.count").value(0));
 }

 @Test
 @DisplayName("DELETE /api/sessions/{id}/messages/{mid} —— 成对删除并返回两个 id")
 void deleteMessageDeletesItsPair() throws Exception {
 Conversation conv = new Conversation();
 conv.setId(10L);
 conv.setUserId(1L);
 when(conversationRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(conv));

 Message userMsg = new Message();
 userMsg.setId(1L);
 userMsg.setConversationId(10L);
 userMsg.setRole("user");
 userMsg.setContent("q");
 when(messageRepository.findByIdAndConversationId(1L, 10L)).thenReturn(Optional.of(userMsg));

 Message aiMsg = new Message();
 aiMsg.setId(2L);
 aiMsg.setConversationId(10L);
 aiMsg.setRole("assistant");
 aiMsg.setContent("a");
 // 删用户提问 → 应当连带删掉它之后的助手回答
 when(messageRepository.findFirstByConversationIdAndRoleAndIdGreaterThanOrderByIdAsc(
 10L, "assistant", 1L)).thenReturn(Optional.of(aiMsg));
 when(messageRepository.findByConversationIdOrderByIdAsc(10L)).thenReturn(List.of());

 mockMvc.perform(delete("/api/sessions/10/messages/1")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.status").value("deleted"))
 // 必须同时返回两条 id —— 前端据此从本地列表移除两个气泡，
 // 否则界面上会留下孤立的半轮问答
 .andExpect(jsonPath("$.ids.length()").value(2))
 .andExpect(jsonPath("$.ids[0]").value(1))
 .andExpect(jsonPath("$.ids[1]").value(2));
 }

 // ======================================================================
 // 五、天气与历史检索
 // ======================================================================

 @Test
 @DisplayName("GET /api/weather —— 未配置天气 Key 时返回 200 + 提示文本（不是错误码）")
 void weatherWithoutKeyReturnsHintText() throws Exception {
 // 这是有意的设计：未配置 Key 时把提示当"正常结果"返回，
 // 前端直接显示在天气卡片里，用户立刻知道要去配置。
 // 若返回 4xx，前端只能显示一个笼统的错误，用户不知道该怎么办。
 mockMvc.perform(get("/api/weather")
 .header("Authorization", "Bearer " + token())
 .param("city", "北京"))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.city").value("北京"))
 .andExpect(jsonPath("$.mode").value("now"))
 .andExpect(jsonPath("$.result").isNotEmpty());
 }

 @Test
 @DisplayName("GET /api/weather —— days 超出 1~4 返回 422（与 接口框架 的 Query 约束一致）")
 void weatherRejectsOutOfRangeDays() throws Exception {
 // 这条用例同时在守一个方法级校验的坑：@RequestParam 上的 @Min/@Max
 // 必须有 @Validated 才生效，否则会被静默忽略、请求照常 200。
 // 所以断言 422 而不是 200，能防止将来有人把 @Validated 删掉。
 mockMvc.perform(get("/api/weather")
 .header("Authorization", "Bearer " + token())
 .param("city", "北京")
 .param("days", "9"))
 .andExpect(status().isUnprocessableEntity())
 .andExpect(jsonPath("$.detail").isNotEmpty());
 }

 @Test
 @DisplayName("GET /api/weather —— 缺少必填的 city 返回 422")
 void weatherRequiresCity() throws Exception {
 mockMvc.perform(get("/api/weather")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isUnprocessableEntity())
 .andExpect(jsonPath("$.detail").isNotEmpty());
 }

 @Test
 @DisplayName("GET /api/search —— 标题命中排在正文命中之前，且 role=title")
 void searchReturnsTitleHitsFirst() throws Exception {
 Conversation conv = new Conversation();
 conv.setId(10L);
 conv.setUserId(1L);
 conv.setTitle("关于迁移方案的讨论");
 when(conversationRepository
 .findByUserIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(anyLong(), anyString(), any()))
 .thenReturn(List.of(conv));
 when(messageRepository.searchInUserConversations(anyLong(), anyString(), any()))
 .thenReturn(List.of());

 mockMvc.perform(get("/api/search")
 .header("Authorization", "Bearer " + token())
 .param("q", "迁移"))
 .andExpect(status().isOk())
 // 标题命中的 role 是第三种取值 "title"，前端按它决定图标与跳转
 .andExpect(jsonPath("$[0].role").value("title"))
 .andExpect(jsonPath("$[0].conversation_id").value(10))
 .andExpect(jsonPath("$[0].content").value("会话标题：关于迁移方案的讨论"));
 }

 @Test
 @DisplayName("GET /api/search —— 关键词超长（>100）返回 422")
 void searchRejectsTooLongQuery() throws Exception {
 mockMvc.perform(get("/api/search")
 .header("Authorization", "Bearer " + token())
 .param("q", "a".repeat(101)))
 .andExpect(status().isUnprocessableEntity())
 .andExpect(jsonPath("$.detail").isNotEmpty());
 }

 // ======================================================================
 // 六、知识库（kb）与文件（files）—— 第四阶段 RAG 写入侧
 //
 // ⚠️ 这里的用例刻意【不触发真实的向量化调用】：
 // KbService.indexFile 会经 EmbeddingService 去连本机 Ollama，而测试环境没有它。
 // 所以涉及索引的用例一律选用【图片】这类"不可索引"的文件 ——
 // FileService.upload 对图片不调 indexFile（isIndexable 为 false），
 // 用例因此保持确定、快速，不依赖外部服务。
 // ======================================================================

 @Test
 @DisplayName("GET /api/kb/collections —— 不带 token 返回 401（新接口已纳入鉴权链）")
 void kbEndpointsRequireAuth() throws Exception {
 mockMvc.perform(get("/api/kb/collections"))
 .andExpect(status().isUnauthorized())
 .andExpect(jsonPath("$.detail").value("认证失败"));
 }

 @Test
 @DisplayName("GET /api/kb/collections —— 没有库时返回空数组而不是 null（前端要 .map）")
 void listCollectionsReturnsEmptyArray() throws Exception {
 when(kbCollectionRepository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of());

 mockMvc.perform(get("/api/kb/collections")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 // 返回 null 会让前端的 list.map(...) 直接崩，所以必须是数组
 .andExpect(jsonPath("$").isArray());
 }

 @Test
 @DisplayName("POST /api/kb/collections —— 新建成功，字段 snake_case 且统计为 0")
 void createCollectionReturnsSnakeCaseShape() throws Exception {
 when(kbCollectionRepository.save(any(KbCollection.class))).thenAnswer(inv -> {
 KbCollection c = inv.getArgument(0);
 c.setId(3L);
 return c;
 });

 mockMvc.perform(post("/api/kb/collections")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"name":"课程笔记"}
 """))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.id").value(3))
 .andExpect(jsonPath("$.name").value("课程笔记"))
 .andExpect(jsonPath("$.files").value(0))
 .andExpect(jsonPath("$.chunks").value(0))
 // 新建库没有专属 Top-K：null = 跟随全局默认。
 // 这与 0 语义完全不同（0 是非法值，校验范围 1~20），
 // 前端靠 null 判断要不要显示"跟随全局"，所以不能写成 0。
 .andExpect(jsonPath("$.top_k").value(org.hamcrest.Matchers.nullValue()));
 }

 @Test
 @DisplayName("POST /api/kb/collections —— 空名称返回 422（@Size(min=1) 拦住）")
 void createCollectionRejectsEmptyName() throws Exception {
 mockMvc.perform(post("/api/kb/collections")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"name":""}
 """))
 .andExpect(status().isUnprocessableEntity())
 .andExpect(jsonPath("$.detail").isNotEmpty());
 }

 @Test
 @DisplayName("GET /api/kb/rag-config —— 四个检索参数齐全，值与 application.yml 默认一致")
 void ragConfigDefaults() throws Exception {
 mockMvc.perform(get("/api/kb/rag-config")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.top_k").value(4))
 .andExpect(jsonPath("$.chunk_size").value(500))
 .andExpect(jsonPath("$.chunk_overlap").value(80))
 .andExpect(jsonPath("$.embed_batch").value(32));
 }

 @Test
 @DisplayName("POST /api/kb/rag-config —— 改 Top-K 后响应即返回新值（对应回写 .env 立即生效）")
 void setRagConfigReturnsNewTopK() throws Exception {
 // 早期设计在此会把 RAG_TOP_K 回写 .env；Java 侧改为落库 app_settings。
 // 无论哪种持久化方式，"响应里立刻返回新值"这条契约不变。
 mockMvc.perform(post("/api/kb/rag-config")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"top_k":10}
 """))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.top_k").value(10));
 }

 @Test
 @DisplayName("POST /api/kb/rag-config —— top_k 越界按早期设计语义夹取，不报错（99→20 / 0→1）")
 void setRagConfigClampsOutOfRangeTopK() throws Exception {
 // 早期设计 数据校验框架 侧 top_k 只是 int、没有范围约束，越界不会被 422 拒掉，
 // 而是在处理函数里 max(1, min(20, ...)) 夹取。这里固化"夹取而非报错"这条契约：
 // 若哪天加了范围校验变成 422，前端设置页的保存就会莫名失败。
 mockMvc.perform(post("/api/kb/rag-config")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"top_k":99}
 """))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.top_k").value(20));

 mockMvc.perform(post("/api/kb/rag-config")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"top_k":0}
 """))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.top_k").value(1));
 }

 @Test
 @DisplayName("POST /api/kb/rag-config —— 响应只回 top_k，不回传另三个参数（与早期设计一致）")
 void setRagConfigReturnsOnlyTopK() throws Exception {
 // 早期设计是 return {"top_k": k}。若顺手复用 GET 的四个字段，
 // 虽然前端大概率只读 top_k，但已属契约走样，故在此固化。
 mockMvc.perform(post("/api/kb/rag-config")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"top_k":6}
 """))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.top_k").value(6))
 .andExpect(jsonPath("$.chunk_size").doesNotExist())
 .andExpect(jsonPath("$.chunk_overlap").doesNotExist())
 .andExpect(jsonPath("$.embed_batch").doesNotExist());
 }

 @Test
 @DisplayName("GET /api/kb/graph —— 缺 collection_id 返回 422（与早期设计 Query(required) 一致）")
 void graphRequiresCollectionId() throws Exception {
 mockMvc.perform(get("/api/kb/graph")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isUnprocessableEntity())
 .andExpect(jsonPath("$.detail").isNotEmpty());
 }

 @Test
 @DisplayName("PATCH /api/kb/collections/{id} —— 别人的库返回 404 而不是 403")
 void updateOthersCollectionReturns404() throws Exception {
 when(kbCollectionRepository.findByIdAndUserId(999L, 1L)).thenReturn(Optional.empty());

 mockMvc.perform(patch("/api/kb/collections/999")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"top_k":5}
 """))
 .andExpect(status().isNotFound())
 .andExpect(jsonPath("$.detail").value("知识库不存在或无权访问"));
 }

 @Test
 @DisplayName("DELETE /api/kb/collections/{id} —— 不存在返回 404")
 void deleteUnknownCollectionReturns404() throws Exception {
 when(kbCollectionRepository.findByIdAndUserId(999L, 1L)).thenReturn(Optional.empty());

 mockMvc.perform(delete("/api/kb/collections/999")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isNotFound())
 .andExpect(jsonPath("$.detail").value("知识库不存在或无权访问"));
 }

 @Test
 @DisplayName("GET /api/files/limits —— 返回上传限制与分片参数，供前端展示")
 void fileLimitsShape() throws Exception {
 mockMvc.perform(get("/api/files/limits")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.max_mb").value(50))
 .andExpect(jsonPath("$.max_content_chars").value(500000))
 // groups 是「类型分组」数组，前端直接拿它渲染可选类型说明
 .andExpect(jsonPath("$.groups").isArray())
 .andExpect(jsonPath("$.groups[0].label").isNotEmpty())
 .andExpect(jsonPath("$.groups[0].exts").isArray())
 .andExpect(jsonPath("$.all_exts").isArray())
 .andExpect(jsonPath("$.top_k").value(4))
 .andExpect(jsonPath("$.chunk_size").value(500))
 .andExpect(jsonPath("$.chunk_overlap").value(80));
 }

 @Test
 @DisplayName("GET /api/files —— 无文件时返回空数组")
 void listFilesReturnsEmptyArray() throws Exception {
 when(fileItemRepository.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of());

 mockMvc.perform(get("/api/files")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$").isArray());
 }

 @Test
 @DisplayName("POST /api/files —— 上传图片成功（图片不抽正文不进索引，故不依赖嵌入服务）")
 void uploadImageSucceedsWithoutEmbedding() throws Exception {
 // ⚠️ 本用例会真实写盘（FilesService 走真实逻辑，只有仓储是 Mock）。
 // FileService 的落盘逻辑是 dataDir/uploads/u{userId}/{uuid}{ext}，
 // 如果不清理，每次 `mvn test` 都会在 data/uploads/u1/ 下累积一个文件。
 // 这里把「相对数据目录的落盘路径」从 save() 参数里截获，测试结束按同一基准删掉，
 // 顺带删掉随之空掉的 u1 目录（deleteIfExists 只删空目录，确保安全）。
 // 用 String[1] 作 holder —— lambda 内不能给外层局部变量赋值。
 String[] storedPath = new String[1];
 when(fileItemRepository.save(any(FileItem.class))).thenAnswer(inv -> {
 FileItem f = inv.getArgument(0);
 f.setId(11L);
 storedPath[0] = f.getStoredPath();
 return f;
 });

 try {
 MockMultipartFile file = new MockMultipartFile(
 "file", "logo.png", MediaType.IMAGE_PNG_VALUE, "fake-image-bytes".getBytes());

 mockMvc.perform(multipart("/api/files").file(file)
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.id").value(11))
 .andExpect(jsonPath("$.filename").value("logo.png"));
 } finally {
 if (storedPath[0] != null) {
 // 与 FileService 相同基准：相对路径 → 当前数据目录下解析
 // （test profile 里 dataDirProvider.dataDir() 会回落到 appProperties.dataDir()，
 // 故这里直接用 appProperties，与"节假日缓存"用例同一套基准）
 Path uploaded = Paths.get(appProperties.dataDir()).resolve(storedPath[0]);
 Files.deleteIfExists(uploaded);
 Files.deleteIfExists(uploaded.getParent());
 }
 }
 }

 @Test
 @DisplayName("POST /api/files —— 不支持的类型返回 400 并给出中文提示")
 void uploadRejectsUnsupportedType() throws Exception {
 MockMultipartFile file = new MockMultipartFile(
 "file", "evil.exe", "application/octet-stream", "x".getBytes());

 mockMvc.perform(multipart("/api/files").file(file)
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail").isNotEmpty());
 }

 @Test
 @DisplayName("POST /api/files —— .doc 给出专门提示（让用户另存为 .docx）")
 void uploadRejectsLegacyDoc() throws Exception {
 // 老 .doc 是二进制格式、无法零依赖解析，所以单独给一条可操作的提示，
 // 而不是笼统的"不支持的类型"——用户看到就知道该怎么办
 MockMultipartFile file = new MockMultipartFile(
 "file", "old.doc", "application/msword", "x".getBytes());

 mockMvc.perform(multipart("/api/files").file(file)
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail")
 .value("暂不支持旧版 .doc，请在 Word 中另存为 .docx 后再上传"));
 }

 @Test
 @DisplayName("GET /api/files/{id} —— 别人的文档返回 404（不泄露存在性）")
 void getOthersFileReturns404() throws Exception {
 when(fileItemRepository.findByIdAndUserId(999L, 1L)).thenReturn(Optional.empty());

 mockMvc.perform(get("/api/files/999")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isNotFound())
 .andExpect(jsonPath("$.detail").value("文档不存在或无权访问"));
 }

 // ======================================================================
 // 七、节假日（calendar）
 //
 // ⚠️ 同样【不触发真实联网】：在线分支会去连 timor.tech，测试环境不该依赖外网。
 // 所以这里只测两件确定能测的事：
 // 1) 参数校验与鉴权（不进业务层，零 IO）；
 // 2) 【预置磁盘缓存】让请求走 cache 分支 —— 既覆盖了响应契约，又不联网。
 // "在线拉取 + 归一化"留给端到端联调验证。
 // ======================================================================

 @Test
 @DisplayName("GET /api/calendar/holidays —— 不带 token 返回 401")
 void holidaysRequireAuth() throws Exception {
 mockMvc.perform(get("/api/calendar/holidays").param("year", "2026"))
 .andExpect(status().isUnauthorized())
 .andExpect(jsonPath("$.detail").value("认证失败"));
 }

 @Test
 @DisplayName("GET /api/calendar/holidays —— 年份越界返回 422（@Validated 生效）")
 void holidaysRejectOutOfRangeYear() throws Exception {
 // 这条同时在守一个坑：@RequestParam 上的 @Min/@Max 必须有 @Validated 才生效，
 // 否则会被静默忽略、请求照常打到数据源。断言 422 能防止将来有人删掉它。
 mockMvc.perform(get("/api/calendar/holidays")
 .header("Authorization", "Bearer " + token())
 .param("year", "1969"))
 .andExpect(status().isUnprocessableEntity())
 .andExpect(jsonPath("$.detail").isNotEmpty());
 }

 @Test
 @DisplayName("GET /api/calendar/holidays —— 缺少 year 返回 422")
 void holidaysRequireYear() throws Exception {
 mockMvc.perform(get("/api/calendar/holidays")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isUnprocessableEntity())
 .andExpect(jsonPath("$.detail").isNotEmpty());
 }

 // ======================================================================
 // 八、系统设置（system）—— 数据目录
 //
 // ⚠️ 成功路径用【临时目录 + migrate=false】，既不搬运真实数据，
 // 也因为仓储是 mock（写入不落地）而不会污染其它用例。
 // 真正的文件搬运留给端到端验证。
 // ======================================================================

 @Test
 @DisplayName("GET /api/system/data-dir —— 不带 token 返回 401")
 void dataDirRequiresAuth() throws Exception {
 mockMvc.perform(get("/api/system/data-dir"))
 .andExpect(status().isUnauthorized())
 .andExpect(jsonPath("$.detail").value("认证失败"));
 }

 @Test
 @DisplayName("GET /api/system/data-dir —— 返回 current/default/is_default/size_mb/db_exists")
 void dataDirInfoShape() throws Exception {
 mockMvc.perform(get("/api/system/data-dir")
 .header("Authorization", "Bearer " + token()))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.current").isNotEmpty())
 .andExpect(jsonPath("$.default").isNotEmpty())
 // 测试环境没在库里配过数据目录，所以当前就是默认位置
 .andExpect(jsonPath("$.is_default").value(true))
 .andExpect(jsonPath("$.size_mb").isNumber())
 // 契约测试排除了数据源，因此这里必然是 false（可选注入生效）
 .andExpect(jsonPath("$.db_exists").value(false));
 }

 @Test
 @DisplayName("POST /api/system/data-dir —— 成功返回 ok=true 且 need_restart=false（无需重启）")
 void setDataDirSucceedsWithoutRestart() throws Exception {
 // 早期设计写 .env 必须重启（need_restart=true）；本实现落库 + DataDirProvider 每次读，
 // 因此立即生效。用临时目录 + migrate=false，不搬运任何真实数据。
 Path target = Files.createTempDirectory("ipas-datadir-test");
 try {
 mockMvc.perform(post("/api/system/data-dir")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"path":"%s","migrate":false}
 """.formatted(target.toString().replace("\\", "\\\\"))))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.ok").value(true))
 .andExpect(jsonPath("$.path").value(target.toString()))
 .andExpect(jsonPath("$.migrated").value(false))
 .andExpect(jsonPath("$.need_restart").value(false));
 } finally {
 Files.deleteIfExists(target);
 }
 }

 @Test
 @DisplayName("POST /api/system/data-dir —— 空路径返回 400")
 void setDataDirRejectsEmptyPath() throws Exception {
 mockMvc.perform(post("/api/system/data-dir")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"path":" "}
 """))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail").value("请填写路径"));
 }

 @Test
 @DisplayName("POST /api/system/data-dir —— 相对路径返回 400（必须是绝对路径）")
 void setDataDirRejectsRelativePath() throws Exception {
 mockMvc.perform(post("/api/system/data-dir")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"path":"./somewhere"}
 """))
 .andExpect(status().isBadRequest())
 .andExpect(jsonPath("$.detail").value("请填写绝对路径（如 D:/ipas-data）"));
 }

 @Test
 @DisplayName("POST /api/system/data-dir —— 缺少 path 字段返回 422（@Valid 生效）")
 void setDataDirRequiresPath() throws Exception {
 // 早期设计 数据校验框架 的 path: str 是必填，缺字段 422。
 // 这条同时守住 @RequestBody 上的 @Valid —— 漏了它就会变成 400。
 mockMvc.perform(post("/api/system/data-dir")
 .header("Authorization", "Bearer " + token())
 .contentType(MediaType.APPLICATION_JSON)
 .content("""
 {"migrate":true}
 """))
 .andExpect(status().isUnprocessableEntity())
 .andExpect(jsonPath("$.detail").isNotEmpty());
 }

 @Test
 @DisplayName("GET /api/calendar/holidays —— 命中磁盘缓存时返回 cache 来源与完整字段")
 void holidaysFromCache() throws Exception {
 // 预置一份缓存（ts 取当前时间，落在 7 天有效期内），让请求走 cache 分支，
 // 从而在不联网的前提下验证响应契约。用完即删，避免污染后续用例。
 Path cacheDir = Paths.get(appProperties.dataDir()).resolve("cache");
 Files.createDirectories(cacheDir);
 Path cacheFile = cacheDir.resolve("holiday-2026.json");
 String json = """
 {"year":2026,"ts":%d,"days":{"2026-10-01":{"off":true,"work":false,
 "name":"国庆节","wage":3}}}
 """.formatted(System.currentTimeMillis() / 1000);
 Files.writeString(cacheFile, json);

 try {
 mockMvc.perform(get("/api/calendar/holidays")
 .header("Authorization", "Bearer " + token())
 .param("year", "2026"))
 .andExpect(status().isOk())
 .andExpect(jsonPath("$.year").value(2026))
 .andExpect(jsonPath("$.source").value("cache"))
 // 日期是 Map 的 key（含短横线），必须用方括号 + 引号取值
 .andExpect(jsonPath("$.days['2026-10-01'].off").value(true))
 .andExpect(jsonPath("$.days['2026-10-01'].work").value(false))
 .andExpect(jsonPath("$.days['2026-10-01'].name").value("国庆节"))
 .andExpect(jsonPath("$.days['2026-10-01'].wage").value(3));
 } finally {
 Files.deleteIfExists(cacheFile);
 }
 }
}
