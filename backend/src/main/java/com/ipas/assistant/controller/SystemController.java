package com.ipas.assistant.controller;

import com.ipas.assistant.dto.SystemDtos;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.SystemService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统设置接口。对应 的
 * {@code APIRouter(prefix="/api/system")}。
 *
 * <p>目前只有「数据目录」一组接口：查看当前位置、迁移到新位置。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

 private final SystemService systemService;

 public SystemController(SystemService systemService) {
 this.systemService = systemService;
 }

 /**
 * GET /api/system/data-dir —— 当前数据目录信息。
 *
 * <p>响应里的 {@code default} 是 Java 关键字，靠
 * {@code @JsonProperty("default")} 输出（见 {@link SystemDtos.DataDirInfo}）。
 */
 @GetMapping("/data-dir")
 public ResponseEntity<SystemDtos.DataDirInfo> getDataDir(
 @AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(systemService.getDataDir());
 }

 /**
 * POST /api/system/data-dir —— 迁移数据目录。
 *
 * <p>与早期设计不同：<b>改完立即生效，不需要重启后端</b>
 * （早期设计写 .env 必须重启）。{@code need_restart} 因此恒为 false，
 * 字段保留是为了让前端的提示逻辑不变。
 *
 * <p>请求体用了 {@code @Valid}：早期设计 数据校验框架 的
 * {@code path: str} 是必填字段，缺了会 422；这里靠它保持一致
 * （漏写 {@code @Valid} 会让校验静默失效）。
 */
 @PostMapping("/data-dir")
 public ResponseEntity<SystemDtos.DataDirResult> setDataDir(
 @AuthenticationPrincipal AuthUser me,
 @Valid @RequestBody SystemDtos.DataDirUpdate payload) {
 return ResponseEntity.ok(
 systemService.setDataDir(payload.path(), payload.migrateOrDefault()));
 }
}
