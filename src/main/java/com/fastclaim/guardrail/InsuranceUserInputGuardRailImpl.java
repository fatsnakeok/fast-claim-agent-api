package com.fastclaim.guardrail;

import com.embabel.agent.api.validation.guardrails.UserInputGuardRail;
import com.embabel.agent.core.Blackboard;
import com.embabel.common.core.validation.ValidationError;
import com.embabel.common.core.validation.ValidationResult;
import com.embabel.common.core.validation.ValidationSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InsuranceUserInputGuardRailImpl implements UserInputGuardRail {

    private static final Logger log = LoggerFactory.getLogger(InsuranceUserInputGuardRailImpl.class);

    // 注入指令黑名单
    private static final List<String> INJECTION_PATTERNS = List.of(
            "ignore all rules", "ignore previous instructions",
            "auto approve", "bypass review", "bypass",
            "system prompt", "you are now", "act as",
            "admin mode", "developer mode", "jailbreak",
            " pretend ", "new instructions"
    );

    // SQL 注入模式
    private static final List<String> SQL_PATTERNS = List.of(
            "DROP TABLE", "INSERT INTO", "DELETE FROM",
            "UPDATE .* SET", "SELECT .* FROM",
            "' OR '1'='1", "'; --", "' OR 1=1"
    );

    // 无关话题关键词
    private static final List<String> OFF_TOPIC_PATTERNS = List.of(
            "stock", "weather", "sports", "politics", "election",
            "gambling", "cryptocurrency", "bitcoin", "ethereum",
            "recipe", "cooking", "movie", "music", "game"
    );

    private static final Pattern BASE64_PATTERN = Pattern.compile("[A-Za-z0-9+/]{20,}={0,2}");

    @Override
    public String getName() {
        return "insurance-user-input-guardrail";
    }

    @Override
    public String getDescription() {
        return "保险客服用户输入护栏 — 检测注入指令、SQL注入、Base64注入、无关话题";
    }

    @Override
    public ValidationResult validate(String userInput, Blackboard blackboard) {
        String lower = userInput.toLowerCase();

        // 1. 注入指令检测
        String hitInjection = INJECTION_PATTERNS.stream()
                .filter(lower::contains)
                .findFirst().orElse(null);
        if (hitInjection != null) {
            log.warn("输入护栏 — 检测到注入指令: {}", hitInjection);
            return rejectResult("INPUT_REJECTED", "您的消息包含不被允许的内容");
        }

        // 2. Base64 编码注入检测
        if (detectBase64Injection(userInput)) {
            log.warn("输入护栏 — 检测到 Base64 编码注入");
            return rejectResult("INPUT_REJECTED", "您的消息包含不被允许的内容");
        }

        // 3. SQL 注入检测
        String hitSql = SQL_PATTERNS.stream()
                .filter(p -> lower.matches(".*" + Pattern.quote(p.toLowerCase()) + ".*")
                        || lower.contains(p.toLowerCase().replace(" .* ", "")))
                .findFirst().orElse(null);
        if (hitSql != null) {
            log.warn("输入护栏 — 检测到 SQL 注入模式: {}", hitSql);
            return rejectResult("INPUT_REJECTED", "您的消息包含不被允许的内容");
        }

        // 4. 无关话题检测
        String hitTopic = OFF_TOPIC_PATTERNS.stream()
                .filter(lower::contains)
                .findFirst().orElse(null);
        if (hitTopic != null) {
            log.warn("输入护栏 — 检测到无关话题: {}", hitTopic);
            return rejectResult("INPUT_REJECTED", "请咨询保险相关的问题");
        }

        return new ValidationResult(true, List.of());
    }

    private ValidationResult rejectResult(String code, String message) {
        return new ValidationResult(false, List.of(
                new ValidationError(code, message, ValidationSeverity.ERROR)
        ));
    }

    /**
     * 检测 Base64 编码的注入指令 — 提取长 Base64 字符串解码后匹配注入模式。
     */
    private boolean detectBase64Injection(String input) {
        Matcher matcher = BASE64_PATTERN.matcher(input);
        while (matcher.find()) {
            try {
                String decoded = new String(Base64.getDecoder().decode(matcher.group()));
                String lowerDecoded = decoded.toLowerCase();
                if (INJECTION_PATTERNS.stream().anyMatch(lowerDecoded::contains)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // 不是合法 Base64，跳过
            }
        }
        return false;
    }
}
