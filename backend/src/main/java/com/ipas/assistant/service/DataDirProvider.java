package com.ipas.assistant.service;

import com.ipas.assistant.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 数据目录的<b>唯一来源</b>。
 *
 * <h2>为什么必须有这个类</h2>
 *
 * <p>早期设计把 {@code DATA_DIR} 写在 .env 里，进程启动时读一次、之后不再变 ——
 * 所以各处直接 import {@code config.DATA_DIR} 就够了。
 *
 * <p>本实现不一样：数据目录被做成<b>运行时可改</b>（设置页改完立即生效），
 * 而 {@link AppProperties} 是启动时绑定的不可变 record，改不了。
 * 解决办法和 LLM 配置一样走"配置落库"，但这样就有两个可能的值：
 * 库里的（用户改过）和 yml 里的（默认）。
 *
 * <p>如果让每个 Service 自己判断"到底用哪个"，一定会出现
 * <b>文件存到 A 目录、却去 B 目录读</b> 这种不一致 ——
 * 表现是"上传成功、预览/检索却说文件不存在"，极难排查。
 * 所以收敛成一个 Bean 统一裁决，所有读写磁盘的地方都问它。
 *
 * <h2>为什么不缓存</h2>
 *
 * <p>每次调用都查一次库（一次按主键的索引查询），换来的是"改完立刻生效"。
 * 数据目录的读取发生在上传、下载、缓存读写这类低频操作上，
 * 这点开销远小于一致性出错的代价。
 */
@Service
public class DataDirProvider {

 private static final Logger log = LoggerFactory.getLogger(DataDirProvider.class);

 private final RuntimeSettingsService settingsService;
 private final AppProperties properties;

 public DataDirProvider(RuntimeSettingsService settingsService, AppProperties properties) {
 this.settingsService = settingsService;
 this.properties = properties;
 }

 /**
 * 当前生效的数据目录。
 *
 * <p>优先级：库里用户改过的 {@code system.data-dir} ＞ yml 的 {@code app.data-dir}。
 */
 public Path dataDir() {
 String stored = settingsService.getGlobalDataDir();
 if (stored != null && !stored.isBlank()) {
 return Paths.get(stored);
 }
 String fallback = properties.dataDir();
 return Paths.get((fallback == null || fallback.isBlank()) ? "." : fallback);
 }

 /** 上传文件目录：{@code <dataDir>/uploads}。 */
 public Path uploadsDir() {
 return dataDir().resolve("uploads");
 }

 /** 磁盘缓存目录：{@code <dataDir>/cache}。 */
 public Path cacheDir() {
 return dataDir().resolve("cache");
 }

 /**
 * 配置文件里的默认数据目录（未落库时的值），仅供设置页展示"默认位置"用。
 *
 * <p>注意与 {@link #dataDir()} 区分：这个是"出厂默认值"，那个是"当前实际值"。
 */
 public Path defaultDataDir() {
 String fallback = properties.dataDir();
 return Paths.get((fallback == null || fallback.isBlank()) ? "." : fallback);
 }

 /** 当前数据目录是否就是默认位置。 */
 public boolean isDefault() {
 return dataDir().toAbsolutePath().normalize()
 .equals(defaultDataDir().toAbsolutePath().normalize());
 }

 /**
 * 把存储在数据库里的<b>相对</b>路径解析成磁盘绝对路径。
 *
 * <p>{@code files.stored_path} 存的是相对数据目录的路径（如 {@code uploads/u1/xx.md}），
 * 这样整个数据目录被搬走后记录依然有效 —— 前提是要相对于<b>当前</b>数据目录解析，
 * 而不是相对于启动时那个。
 */
 public Path resolveStored(String storedPath) {
 return dataDir().resolve(storedPath);
 }

 /** 记录一条调试日志（目录变更时便于确认生效）。 */
 public void logCurrent(String when) {
 log.info("数据目录（{}）：{}", when, dataDir().toAbsolutePath().normalize());
 }
}
