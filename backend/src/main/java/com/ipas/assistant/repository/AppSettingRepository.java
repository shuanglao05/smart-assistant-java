package com.ipas.assistant.repository;

import com.ipas.assistant.entity.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 运行时配置仓储。
 */
@Repository
public interface AppSettingRepository extends JpaRepository<AppSetting, Long> {

 /** 取单个配置项。 */
 Optional<AppSetting> findByUserIdAndSettingKey(Long userId, String settingKey);

 /** 批量取多个配置项（读一次库拿全，避免逐项查询）。 */
 List<AppSetting> findByUserIdAndSettingKeyIn(Long userId, List<String> settingKeys);

 /** 删除某个配置项（用于"清空为默认值"语义）。 */
 void deleteByUserIdAndSettingKey(Long userId, String settingKey);
}
