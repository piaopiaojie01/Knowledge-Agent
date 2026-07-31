package com.ka.repository;

import com.ka.entity.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    /** 最近 200 条反馈（管理员查看用） */
    List<Feedback> findTop200ByOrderByCreatedAtDesc();
}
