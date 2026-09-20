package com.ipas.assistant.dto.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 注册请求体。对应 {@code schemas.RegisterRequest}：
 * <pre>
 * class RegisterRequest(BaseModel):
 * username: str = Field(min_length=1, max_length=50)
 * password: str = Field(min_length=1, max_length=128)
 * </pre>
 *
 * <h2>关于校验注解的选择（这里有个容易做错的细节）</h2>
 *
 * <p>直觉上会用 {@code @NotBlank} 来「拒绝空用户名」，但那样会改变接口行为。
 * 对比一下三种输入在<b>早期设计</b>下的结果：
 *
 * <table border="1">
 * <tr><th>输入</th><th>数据校验框架 结果</th><th>最终响应</th></tr>
 * <tr><td>完全没传 username</td><td>缺必填字段 → 422</td><td>422</td></tr>
 * <tr><td>{@code ""}（空串）</td><td>min_length=1 不满足 → 422</td><td>422</td></tr>
 * <tr><td>{@code " "}（三个空格）</td><td>长度为 3，<b>通过</b> 数据校验框架</td>
 * <td>进入业务层，{@code username.strip()} 后为空 →
 * <b>400「用户名不能为空」</b></td></tr>
 * </table>
 *
 * <p>也就是说「纯空格」在早期设计下是 <b>400</b> 而不是 422。若这里用
 * {@code @NotBlank}，纯空格会在进入业务层之前就被拦下并返回 422，
 * 与前端已适配的行为不一致。
 *
 * <p>因此这里用 {@code @NotNull}（挡住「没传」）+ {@code @Size(min = 1)}
 * （挡住空串），把「纯空格」留给业务层判断 —— 这样三种输入的结果与早期设计完全一致。
 * 同样的道理也适用于 {@code password}：早期设计对 {@code " "} 这种密码是<b>接受</b>的
 * （长度为 3 能通过 min_length=1），这里也不该额外拒绝。
 *
 * <p>{@code @Size} 的上下限不是随手写的：{@code username} 的 50 对应数据库列
 * {@code VARCHAR(50)}；若校验比数据库列宽，超长输入会被 MySQL 截断，
 * 表现为「注册成功但用户名少了一截」。
 */
public record RegisterRequest(

 @NotNull(message = "用户名不能为空")
 @Size(min = 1, max = 50, message = "用户名长度需在 1~50 个字符之间")
 String username,

 @NotNull(message = "密码不能为空")
 @Size(min = 1, max = 128, message = "密码长度需在 1~128 个字符之间")
 String password
) {
}
