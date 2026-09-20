package com.ipas.assistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 读取应用版本号。
 *
 * <p>对应 里的 {@code _read_version()}：
 * <pre>
 * root = Path(__file__).resolve().parents[2] # .../smart-assistant
 * return (root / "VERSION").read_text(encoding="utf-8").strip() or "1.0.0"
 * </pre>
 *
 * <p>早期设计的设计意图值得保留：<b>版本号只有一个来源</b> —— 仓库根目录的
 * {@code VERSION} 文件。发版时只改这一个文件，不用去 pom.xml、前端 package.json
 * 里各改一遍（那种做法最容易漏改，导致前后端版本号对不上）。
 *
 * <p>这里按顺序找两个位置，都找不到就回落到 {@code "1.0.0"}：
 * <ol>
 * <li>{@code ./VERSION} —— Java 项目自己的版本文件（<b>主来源</b>）；
 * <li>{@code ../smart-assistant/VERSION} —— 原 早期实现的版本文件。
 * 放在这里是为了「两个版本并行对照」期间，本实现能显示与 早期实现
 * 相同的版本号，方便确认测的是哪一版。</li>
 * </ol>
 *
 * <p>刻意<b>不让文件缺失导致启动失败</b>：读不到就用默认值。版本号只用于
 * {@code /api/health} 展示，不该成为启动的硬依赖。
 */
@Component
public class AppVersion {

 private static final Logger log = LoggerFactory.getLogger(AppVersion.class);

 /** 读不到 VERSION 文件时的兜底版本号（与早期设计一致）。 */
 private static final String FALLBACK = "1.0.0";

 private final String version;

 public AppVersion() {
 this.version = readVersion();
 }

 /** @return 版本号字符串，例如 {@code "1.5.0"} */
 public String get() {
 return version;
 }

 private static String readVersion() {
 for (String candidate : new String[]{"VERSION", "../smart-assistant/VERSION"}) {
 Path path = Paths.get(candidate);
 try {
 if (Files.isRegularFile(path)) {
 String text = Files.readString(path, StandardCharsets.UTF_8).trim();
 if (!text.isEmpty()) {
 return text;
 }
 }
 } catch (IOException e) {
 // 单个候选读取失败不算问题，继续试下一个
 log.debug("读取版本文件 {} 失败：{}", path.toAbsolutePath(), e.getMessage());
 }
 }
 log.debug("未找到 VERSION 文件，版本号回落到 {}", FALLBACK);
 return FALLBACK;
 }
}
