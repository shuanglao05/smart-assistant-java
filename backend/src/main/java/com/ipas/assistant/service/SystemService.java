package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.dto.SystemDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * 系统级设置：数据目录的查看与迁移。对应 。
 *
 * <h2>与早期设计的两处语义差异（都是架构变化导致的，不是疏漏）</h2>
 *
 * <ol>
 * <li><b>不再迁移数据库</b>：早期设计要把 SQLite 的 {@code app.db} 用在线备份 API 复制到新位置；
 * 本实现数据库是 <b>MySQL 独立服务</b>，压根不在数据目录里，
 * 所以迁移只剩 {@code uploads/} 与 {@code cache/} 两个子目录。</li>
 * <li><b>改完立即生效、无需重启</b>：早期设计写 .env 而配置在导入时固定，只能重启后生效
 * （返回 {@code need_restart: true}）。本实现落库到 app_settings，
 * 由 {@link DataDirProvider} 每次读，所以 {@code need_restart} 恒为 false。</li>
 * </ol>
 */
@Service
public class SystemService {

 private static final Logger log = LoggerFactory.getLogger(SystemService.class);

 /** 迁移时要搬运的子目录（数据库不在其中，见类注释）。 */
 private static final List<String> MIGRATE_SUBDIRS = List.of("uploads", "cache");

 private final DataDirProvider dataDirProvider;
 private final RuntimeSettingsService settingsService;
 /**
 * 用 {@link ObjectProvider} 而不是直接注入 {@code DataSource}：
 * 契约测试排除了数据源自动配置，直接注入会让容器启动失败
 * （{@code NoSuchBeanDefinitionException}，表现为全部测试一起 Error）。
 * 可选注入让"没有数据源"成为一种正常运行状态（此时数据库可用性记为 false）。
 */
 private final ObjectProvider<DataSource> dataSourceProvider;

 public SystemService(DataDirProvider dataDirProvider,
 RuntimeSettingsService settingsService,
 ObjectProvider<DataSource> dataSourceProvider) {
 this.dataDirProvider = dataDirProvider;
 this.settingsService = settingsService;
 this.dataSourceProvider = dataSourceProvider;
 }

 // ==================================================================
 // 查看
 // ==================================================================

 /** GET /api/system/data-dir：当前数据目录 + 默认位置 + 占用 + 数据库可用性。 */
 @Transactional(readOnly = true)
 public SystemDtos.DataDirInfo getDataDir() {
 Path current = dataDirProvider.dataDir();
 return new SystemDtos.DataDirInfo(
 current.toAbsolutePath().normalize().toString(),
 dataDirProvider.defaultDataDir().toAbsolutePath().normalize().toString(),
 dataDirProvider.isDefault(),
 dirSizeMb(current),
 databaseReachable());
 }

 // ==================================================================
 // 迁移
 // ==================================================================

 /**
 * POST /api/system/data-dir：把数据目录改到新位置（默认同时复制现有数据）。
 *
 * <p>校验顺序与早期设计一致：<b>先做纯字符串/路径判断，再碰磁盘</b> ——
 * 这样"路径填错"这类最常见的错误不会留下任何副作用。
 */
 @Transactional
 public SystemDtos.DataDirResult setDataDir(String rawPath, boolean migrate) {
 String raw = rawPath == null ? "" : rawPath.strip();
 if (raw.isEmpty()) {
 throw ApiException.badRequest("请填写路径");
 }

 Path target = expandUser(raw);
 if (!target.isAbsolute()) {
 throw ApiException.badRequest("请填写绝对路径（如 D:/ipas-data）");
 }
 target = target.normalize();

 Path current = dataDirProvider.dataDir().toAbsolutePath().normalize();
 if (target.equals(current)) {
 throw ApiException.badRequest("新位置与当前数据目录相同");
 }

 // 不要把数据放到源代码目录里（早期设计防的是 backend/app，这里防 user.dir/src）
 Path srcDir = Paths.get(System.getProperty("user.dir", ".")).resolve("src")
 .toAbsolutePath().normalize();
 if (target.equals(srcDir) || target.startsWith(srcDir)) {
 throw ApiException.badRequest("不要设到源代码目录里");
 }

 try {
 Files.createDirectories(target);
 } catch (Exception e) {
 throw ApiException.badRequest("无法创建目录：" + e.getMessage());
 }

 // Windows 下"能不能写"不能靠权限位判断，实测写一次最可靠（早期设计同款注释）
 try {
 Path test = target.resolve(".ipas_write_test");
 Files.writeString(test, "ok");
 Files.deleteIfExists(test);
 } catch (Exception e) {
 throw ApiException.badRequest("该目录不可写：" + e.getMessage());
 }

 // 非空目录必须是"看起来像数据目录"的，避免覆盖用户别的文件
 if (!isEmptyDir(target) && !looksLikeDataDir(target)) {
 throw ApiException.badRequest(
 "目标目录非空，请选择空目录（或已存放 uploads / cache 的数据目录）");
 }

 boolean migrated = false;
 if (migrate) {
 try {
 for (String sub : MIGRATE_SUBDIRS) {
 Path from = current.resolve(sub);
 if (Files.isDirectory(from)) {
 copyDirectory(from, target.resolve(sub));
 }
 }
 migrated = true;
 } catch (Exception e) {
 throw ApiException.badRequest("数据迁移失败：" + e.getMessage());
 }
 }

 // 落库（全局配置）。DataDirProvider 每次读，所以改完立即生效
 settingsService.put(RuntimeSettingsService.GLOBAL_USER_ID,
 RuntimeSettingsService.KEY_SYSTEM_DATA_DIR,
 target.toString());
 log.info("数据目录已切换到 {}（迁移={}），立即生效无需重启", target, migrated);

 return new SystemDtos.DataDirResult(true, target.toString(), migrated, false);
 }

 // ==================================================================
 // 辅助
 // ==================================================================

 /** 展开 {@code ~} 成用户主目录（对应 {@code Path.expanduser()}）。 */
 private static Path expandUser(String raw) {
 if (raw.equals("~")) {
 return Paths.get(System.getProperty("user.home"));
 }
 if (raw.startsWith("~/") || raw.startsWith("~\\")) {
 return Paths.get(System.getProperty("user.home")).resolve(raw.substring(2));
 }
 return Paths.get(raw);
 }

 /** 目录占用大小（MB，保留 1 位小数）；读不到就当 0，不能因为它让接口失败。 */
 private static double dirSizeMb(Path dir) {
 long total = 0;
 try (Stream<Path> walk = Files.walk(dir)) {
 total = walk.filter(Files::isRegularFile)
 .mapToLong(p -> {
 try {
 return Files.size(p);
 } catch (IOException e) {
 return 0L;
 }
 })
 .sum();
 } catch (Exception e) {
 // 目录不存在 / 无权限：返回 0 即可，设置页显示 0MB 比报错好
 return 0.0;
 }
 return Math.round(total / 1024.0 / 1024.0 * 10) / 10.0;
 }

 /**
 * 数据库是否可用（对应的 {@code db_exists}）。
 *
 * <p>语义已随架构变化：原来是"数据目录里有没有 app.db"，
 * 现在数据库是独立的 MySQL 服务，改为"能不能连上"。
 */
 private boolean databaseReachable() {
 DataSource ds = dataSourceProvider.getIfAvailable();
 if (ds == null) {
 return false;
 }
 try (var connection = ds.getConnection()) {
 return connection != null && !connection.isClosed();
 } catch (Exception e) {
 log.warn("检查数据库可用性失败：{}", e.getMessage());
 return false;
 }
 }

 private static boolean isEmptyDir(Path dir) {
 try (Stream<Path> list = Files.list(dir)) {
 return list.findAny().isEmpty();
 } catch (Exception e) {
 return true;
 }
 }

 /** 目标目录是否像一个已有的数据目录（含 uploads 或 cache）。 */
 private static boolean looksLikeDataDir(Path dir) {
 for (String sub : MIGRATE_SUBDIRS) {
 if (Files.isDirectory(dir.resolve(sub))) {
 return true;
 }
 }
 return false;
 }

 /** 递归复制目录（目标已存在时覆盖同名文件）。 */
 private static void copyDirectory(Path src, Path dst) throws IOException {
 List<Path> all;
 try (Stream<Path> walk = Files.walk(src)) {
 all = walk.toList();
 }
 for (Path p : all) {
 Path target = dst.resolve(src.relativize(p).toString());
 if (Files.isDirectory(p)) {
 Files.createDirectories(target);
 } else {
 Files.createDirectories(target.getParent());
 Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
 }
 }
 }
}
