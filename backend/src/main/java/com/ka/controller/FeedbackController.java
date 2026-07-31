package com.ka.controller;

import com.ka.config.SecurityUtils;
import com.ka.dto.ApiResponse;
import com.ka.entity.Feedback;
import com.ka.entity.User;
import com.ka.repository.FeedbackRepository;
import com.ka.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;

    private static final int MAX_QUESTION_LENGTH = 10000;
    private static final int MAX_ANSWER_LENGTH = 10000;
    private static final int MAX_COMMENT_LENGTH = 2000;

    @PostMapping
    public ApiResponse<Feedback> submit(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtils.getCurrentUserId();

        Object sessionId = body.get("sessionId");
        if (sessionId != null && !(sessionId instanceof String)) {
            return ApiResponse.error(400, "sessionId 类型错误");
        }
        Object question = body.get("question");
        if (question != null && !(question instanceof String)) {
            return ApiResponse.error(400, "question 类型错误");
        }
        Object answer = body.get("answer");
        if (answer != null && !(answer instanceof String)) {
            return ApiResponse.error(400, "answer 类型错误");
        }
        Object comment = body.get("comment");
        if (comment != null && !(comment instanceof String)) {
            return ApiResponse.error(400, "comment 类型错误");
        }
        if (question instanceof String q && q.length() > MAX_QUESTION_LENGTH) {
            return ApiResponse.error(400, "question 超长（上限 " + MAX_QUESTION_LENGTH + " 字符）");
        }
        if (answer instanceof String a && a.length() > MAX_ANSWER_LENGTH) {
            return ApiResponse.error(400, "answer 超长（上限 " + MAX_ANSWER_LENGTH + " 字符）");
        }
        if (comment instanceof String c && c.length() > MAX_COMMENT_LENGTH) {
            return ApiResponse.error(400, "comment 超长（上限 " + MAX_COMMENT_LENGTH + " 字符）");
        }
        Object rating = body.get("rating");
        if (rating != null && !(rating instanceof Number)) {
            return ApiResponse.error(400, "rating 类型错误");
        }
        // 前端评分语义：1=有帮助，-1=没帮助，0=未评分
        int ratingValue = rating instanceof Number n ? n.intValue() : 0;
        if (ratingValue < -1 || ratingValue > 1) {
            return ApiResponse.error(400, "rating 仅支持 -1 / 0 / 1");
        }

        Feedback feedback = Feedback.builder()
                .userId(userId)
                .sessionId((String) sessionId)
                .question((String) question)
                .answer((String) answer)
                .rating(ratingValue)
                .comment((String) comment)
                .build();
        return ApiResponse.success("反馈已提交", feedbackRepository.save(feedback));
    }

    @GetMapping
    public ApiResponse<List<Feedback>> list() {
        Long userId = SecurityUtils.getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        if (!"ADMIN".equals(user.getRole())) {
            return ApiResponse.error(403, "仅管理员可查看反馈");
        }
        return ApiResponse.success(feedbackRepository.findTop200ByOrderByCreatedAtDesc());
    }
}
