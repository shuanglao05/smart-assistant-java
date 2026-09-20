package com.ipas.assistant.repository;

import com.ipas.assistant.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 课程仓储。对应 。
 */
@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {

 /** 课表渲染顺序：先按星期几，再按起始节次（前端网格就是这么铺的）。 */
 List<Course> findByUserIdOrderByWeekdayAscStartSectionAsc(Long userId);

 Optional<Course> findByIdAndUserId(Long id, Long userId);
}
