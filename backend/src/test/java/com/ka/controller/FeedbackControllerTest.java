package com.ka.controller;

import com.ka.dto.ApiResponse;
import com.ka.entity.Feedback;
import com.ka.repository.FeedbackRepository;
import com.ka.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 反馈提交校验回归测试：rating 合法范围 -1/0/1、字段类型检查、长度上限。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackControllerTest {

    @Mock private FeedbackRepository feedbackRepository;
    @Mock private UserRepository userRepository;

    private FeedbackController controller;

    @BeforeEach
    void setUp() {
        controller = new FeedbackController(feedbackRepository, userRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Map<String, Object> baseBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("question", "问题");
        body.put("answer", "回答");
        body.put("rating", 1);
        return body;
    }

    @Test
    void rating越界返回400() {
        Map<String, Object> body = baseBody();
        body.put("rating", 5);

        ApiResponse<Feedback> resp = controller.submit(body);

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("rating"));
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void rating为负二也返回400() {
        Map<String, Object> body = baseBody();
        body.put("rating", -2);

        ApiResponse<Feedback> resp = controller.submit(body);

        assertEquals(400, resp.getCode());
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void rating传字符串类型错误返回400() {
        Map<String, Object> body = baseBody();
        body.put("rating", "1");

        ApiResponse<Feedback> resp = controller.submit(body);

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("类型"));
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void question超长返回400() {
        Map<String, Object> body = baseBody();
        body.put("question", "长".repeat(10001));

        ApiResponse<Feedback> resp = controller.submit(body);

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("question"));
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void answer超长返回400() {
        Map<String, Object> body = baseBody();
        body.put("answer", "长".repeat(10001));

        ApiResponse<Feedback> resp = controller.submit(body);

        assertEquals(400, resp.getCode());
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void question传非字符串类型返回400() {
        Map<String, Object> body = baseBody();
        body.put("question", 123);

        ApiResponse<Feedback> resp = controller.submit(body);

        assertEquals(400, resp.getCode());
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void 合法反馈正常保存() {
        when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<Feedback> resp = controller.submit(baseBody());

        assertEquals(200, resp.getCode());
        assertEquals(1, resp.getData().getRating());
        assertEquals(1L, resp.getData().getUserId());
        verify(feedbackRepository).save(any(Feedback.class));
    }
}
