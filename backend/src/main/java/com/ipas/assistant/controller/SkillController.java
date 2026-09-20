package com.ipas.assistant.controller;

import com.ipas.assistant.dto.SkillDtos;
import com.ipas.assistant.dto.StatusResponse;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.SkillService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 技能接口。对应 的 {@code APIRouter(prefix="/api/skills")}。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

 private final SkillService skillService;

 public SkillController(SkillService skillService) {
 this.skillService = skillService;
 }

 /** GET /api/skills —— 技能列表。 */
 @GetMapping
 public ResponseEntity<List<SkillDtos.Out>> list(@AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(skillService.list(me.id()));
 }

 /** POST /api/skills —— 新建技能。 */
 @PostMapping
 public ResponseEntity<SkillDtos.Out> create(@AuthenticationPrincipal AuthUser me,
 @Valid @RequestBody SkillDtos.Create payload) {
 return ResponseEntity.ok(skillService.create(me.id(), payload));
 }

 /** PATCH /api/skills/{id} —— 更新技能（含启用/关闭）。 */
 @PatchMapping("/{id}")
 public ResponseEntity<SkillDtos.Out> update(@AuthenticationPrincipal AuthUser me,
 @PathVariable Long id,
 @RequestBody SkillDtos.Update payload) {
 return ResponseEntity.ok(skillService.update(me.id(), id, payload));
 }

 /**
 * DELETE /api/skills/{id} —— 删除技能。
 *
 * <p>注意这个删除<b>不只是删一行</b>：Service 里还会把它从该用户所有会话的
 * {@code active_skill_ids} 中摘掉，两件事在同一事务里完成。
 * 详见 {@code SkillService.delete} 的注释 —— 漏了清理会留下悬空 id，
 * 在第三阶段表现为"勾了技能却不生效"。
 */
 @DeleteMapping("/{id}")
 public ResponseEntity<StatusResponse> delete(@AuthenticationPrincipal AuthUser me,
 @PathVariable Long id) {
 skillService.delete(me.id(), id);
 return ResponseEntity.ok(StatusResponse.deleted());
 }
}
