package com.fastclaim.guardrail;

import com.embabel.agent.api.validation.guardrails.AssistantMessageGuardRail;
import com.embabel.agent.core.Blackboard;
import com.embabel.chat.AssistantMessage;
import com.embabel.common.core.thinking.ThinkingResponse;
import com.embabel.common.core.validation.ValidationError;
import com.embabel.common.core.validation.ValidationResult;
import com.embabel.common.core.validation.ValidationSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class InsuranceAssistantMessageGuardRailImpl implements AssistantMessageGuardRail {

    private static final Logger log = LoggerFactory.getLogger(InsuranceAssistantMessageGuardRailImpl.class);

    private static final Pattern CREDIT_CARD = Pattern.compile("\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}");
    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");
    private static final Pattern PHONE_CN = Pattern.compile("1[3-9]\\d{9}");

    private static final List<String> HALLUCINATION_SIGNALS = List.of(
            "I'm not sure", "I think", "might be", "could be wrong",
            "probably", "possibly", "I guess", "I believe",
            "not certain", "as far as I know"
    );

    private static final List<String> SENSITIVE_KEYWORDS = List.of(
            "password", "密钥", "secret", "token", "api_key", "access_key"
    );

    @Override
    public String getName() {
        return "insurance-assistant-message-guardrail";
    }

    @Override
    public String getDescription() {
        return "保险客服 LLM 回复护栏 — 检测敏感信息、幻觉迹象、长度异常";
    }

    @Override
    public ValidationResult validate(String text, Blackboard blackboard) {
        return validateText(text);
    }

    @Override
    public ValidationResult validate(ThinkingResponse<?> thinkingResponse, Blackboard blackboard) {
        if (thinkingResponse.hasResult() && thinkingResponse.getResult() instanceof String result) {
            return validateText(result);
        }
        return new ValidationResult(true, List.of());
    }

    /**
     * LLM 回复护栏 — 仅标记警告不阻断。
     * 阻断 LLM 回复会导致用户无响应，用户体验不可接受。
     */
    @Override
    public ValidationResult validate(AssistantMessage assistantMessage, Blackboard blackboard) {
        return validateText(assistantMessage.getContent());
    }

    private ValidationResult validateText(String text) {
        List<ValidationError> warnings = new ArrayList<>();

        // 1. 长度异常检测
        if (text.length() < 10) {
            log.warn("回复护栏 — 回复过短 ({} 字符)，可能有质量问题", text.length());
            warnings.add(new ValidationError("SHORT_RESPONSE",
                    "回复过短(" + text.length() + "字符)", ValidationSeverity.WARNING));
        }
        if (text.length() > 5000) {
            log.warn("回复护栏 — 回复过长 ({} 字符)，可能失控", text.length());
            warnings.add(new ValidationError("LONG_RESPONSE",
                    "回复过长(" + text.length() + "字符)", ValidationSeverity.WARNING));
        }

        // 2. 敏感信息检测
        if (CREDIT_CARD.matcher(text).find()) {
            log.warn("回复护栏 — 检测到信用卡号模式");
            warnings.add(new ValidationError("CREDIT_CARD_LEAK", "检测到信用卡号模式", ValidationSeverity.WARNING));
        }
        if (ID_CARD.matcher(text).find()) {
            log.warn("回复护栏 — 检测到身份证号模式");
            warnings.add(new ValidationError("ID_CARD_LEAK", "检测到身份证号模式", ValidationSeverity.WARNING));
        }
        if (PHONE_CN.matcher(text).find()) {
            log.warn("回复护栏 — 检测到手机号模式");
            warnings.add(new ValidationError("PHONE_LEAK", "检测到手机号模式", ValidationSeverity.WARNING));
        }
        String lowerMsg = text.toLowerCase();
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lowerMsg.contains(keyword.toLowerCase())) {
                log.warn("回复护栏 — 检测到敏感关键词: {}", keyword);
                warnings.add(new ValidationError("SENSITIVE_KEYWORD",
                        "检测到敏感关键词: " + keyword, ValidationSeverity.WARNING));
                break;
            }
        }

        // 3. 幻觉迹象检测
        for (String signal : HALLUCINATION_SIGNALS) {
            if (lowerMsg.contains(signal.toLowerCase())) {
                log.warn("回复护栏 — 检测到幻觉迹象: {}", signal);
                warnings.add(new ValidationError("HALLUCINATION_SIGNAL",
                        "检测到幻觉迹象: " + signal, ValidationSeverity.WARNING));
                break;
            }
        }

        // 即使有告警也不阻断，返回 valid=true 使回复正常返回
        return new ValidationResult(true, warnings);
    }
}
