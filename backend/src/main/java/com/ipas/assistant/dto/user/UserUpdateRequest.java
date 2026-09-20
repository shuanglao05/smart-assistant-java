package com.ipas.assistant.dto.user;

import jakarta.validation.constraints.Size;

/**
 * 更新个人资料请求体。对应 {@code schemas.UserUpdate}：
 * <pre>
 * class UserUpdate(BaseModel):
 * nickname: str | None = Field(default=None, max_length=50)
 * avatar: str | None = Field(default=None, max_length=1_000_000)
 * language: str | None = None
 * font_size: str | None = None
 * theme: str | None = None
 * current_password: str | None = None
 * new_password: str | None = Field(default=None, min_length=1, max_length=128)
 * </pre>
 *
 * <p><b>「缺省 vs 显式传 null」的语义</b>：早期设计用
 * {@code if payload.nickname is not None:} 判断「这个字段本次要不要改」，
 * 也就是说<b>只能改、不能清空</b>（传 null 等于不改）。
 * Java 侧沿用同样的判断方式（见 UserService），保证行为一致。
 * 前端 {@code UserUpdatePayload} 里所有字段也都是可选的，与之匹配。
 *
 * <p><b>为什么 language / fontSize / theme 在这里不加枚举校验</b>：
 * 早期设计把校验放在业务层（用户模块 里的 ALLOWED_* 集合 + 中文报错
 * 「不支持的语言: xx」）。若挪到注解上，报错信息会变成框架的默认文案，
 * 与前端已适配的提示不一致。这类「非法值是业务错误、需要中文提示」的场景，
 * 校验留在 Service 层更合适。
 *
 * @param nickname 新昵称；null 表示不修改
 * @param avatar 新头像；null 表示不修改
 * @param language 新语言 zh/en；null 表示不修改
 * @param fontSize 新字号；null 表示不修改
 * @param theme 新主题；null 表示不修改
 * @param currentPassword 当前密码（改密时必填，用于验证身份）
 * @param newPassword 新密码；null 表示不修改密码
 */
public record UserUpdateRequest(

 @Size(max = 50, message = "昵称最长 50 个字符")
 String nickname,

 // 1_000_000：与数据库 MEDIUMTEXT 上限和早期设计 数据校验框架 的 max_length 都对齐。
 // 前端上传头像前已压缩成几十 KB 的 data URL，这个上限只是防滥用
 @Size(max = 1_000_000, message = "头像数据过大")
 String avatar,

 String language,

 String fontSize,

 String theme,

 String currentPassword,

 @Size(min = 1, max = 128, message = "新密码长度需在 1~128 个字符之间")
 String newPassword
) {
}
