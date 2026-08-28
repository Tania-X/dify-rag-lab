package com.example.difyraglab.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** 统一异常处理：把后端错误转成 {error, message}，便于演练排障。 */
@RestControllerAdvice
public class GlobalExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionAdvice.class);

    /** 配置/参数类错误（未配置 Key、非法参数等）→ 400，属客户端问题。 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.getClass().getSimpleName());
        body.put("message", e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    /** 其他异常 → 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handle(Exception e) {
        log.error("请求处理失败", e);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.getClass().getSimpleName());
        body.put("message", e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
