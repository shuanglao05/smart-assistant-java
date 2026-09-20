package com.ipas.assistant;

import com.ipas.assistant.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * <p>对应 早期实现的 ——都负责「装配应用、注册路由、
 * 开 CORS、启动建表」这几件事。区别在于：
 *
 * <ul>
 * <li>接口框架 用 {@code app.include_router(xxx_router)} 逐个手工注册路由；
 * Spring 靠组件扫描自动发现所有 {@code @RestController}，无需手工登记。</li>
 * <li>接口框架 在 {@code @app.on_event("startup")} 里起后台任务；
 * Spring 对应 {@code @Scheduled} 或 {@code ApplicationRunner}。</li>
 * <li>接口框架 的 {@code init_db()} 手工建表；Spring 由
 * {@code spring.sql.init} 执行 {@code db/schema-mysql.sql}。</li>
 * </ul>
 *
 * <p>{@code @EnableConfigurationProperties(AppProperties.class)} 的作用：
 * 把 application.yml 里 {@code app.*} 下的配置绑定到 AppProperties 这个 record 上。
 * 早期设计是在 里用 {@code os.getenv()} 逐个读取 —— 那种写法
 * 配置错了只有运行时才炸；Spring 的绑定在启动时就会校验类型，配错了直接启动失败。
 *
 * <p>{@code @EnableScheduling} 的作用：打开定时任务支持，日程提醒后台任务
 * （{@code ScheduleReminderJob}）依赖它。对应
 * 的 {@code asyncio.create_task(reminder_loop())} ——
 * 接口框架 里必须手工把协程挂到事件循环上，Spring 只需一个注解，
 * 定时任务会被自动发现并按 {@code @Scheduled} 的配置调度。
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
public class AssistantApplication {

 public static void main(String[] args) {
 SpringApplication.run(AssistantApplication.class, args);
 }
}
