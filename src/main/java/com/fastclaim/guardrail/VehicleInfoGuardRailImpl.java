package com.fastclaim.guardrail;

import com.embabel.agent.api.validation.guardrails.UserInputGuardRail;
import com.embabel.agent.core.Blackboard;
import com.embabel.common.core.validation.ValidationError;
import com.embabel.common.core.validation.ValidationResult;
import com.embabel.common.core.validation.ValidationSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 核保输入护栏 — 确保用户输入包含可识别的车辆关键词
 */
@Component
public class VehicleInfoGuardRailImpl implements UserInputGuardRail {

    private static final Logger log = LoggerFactory.getLogger(VehicleInfoGuardRailImpl.class);

    private static final Set<String> BRANDS = Set.of(
            "TOYOTA", "HONDA", "BMW", "TESLA", "BENZ", "MERCEDES", "AUDI",
            "VOLKSWAGEN", "VW", "FORD", "NISSAN", "HYUNDAI", "KIA", "BYD",
            "NIO", "XPENG", "LI AUTO", "GEELY", "CHERY", "GREAT WALL",
            "MAZDA", "SUBARU", "LEXUS", "PORSCHE", "CADILLAC", "VOLVO",
            "丰田", "本田", "宝马", "特斯拉", "奔驰", "奥迪", "大众",
            "福特", "日产", "现代", "起亚", "比亚迪", "蔚来", "小鹏",
            "理想", "吉利", "奇瑞", "长城", "马自达", "斯巴鲁", "雷克萨斯",
            "保时捷", "凯迪拉克", "沃尔沃"
    );

    private static final Pattern LICENSE_PLATE_PATTERN =
            Pattern.compile("[A-Z]{2,3}\\d{3,5}", Pattern.CASE_INSENSITIVE);

    private static final Set<String> VAGUE_PATTERNS = Set.of(
            "我的车", "一辆车", "这台车", "那辆车", "my car", "a car"
    );

    @Override
    public String getName() {
        return "vehicle-info-guardrail";
    }

    @Override
    public String getDescription() {
        return "核保车辆信息护栏 — 检测输入是否包含可识别的车辆品牌/型号/车牌";
    }

    @Override
    public ValidationResult validate(String userInput, Blackboard blackboard) {
        String upper = userInput.toUpperCase();

        boolean hasBrand = BRANDS.stream().anyMatch(upper::contains);
        if (hasBrand) {
            return pass();
        }

        if (LICENSE_PLATE_PATTERN.matcher(userInput).find()) {
            return pass();
        }

        boolean isVague = VAGUE_PATTERNS.stream().anyMatch(v -> userInput.contains(v));
        if (isVague) {
            log.warn("车辆信息护栏 — 仅包含模糊表述，缺少具体品牌/型号");
            return reject("VEHICLE_INFO_INSUFFICIENT",
                    "请提供具体车辆信息（品牌、型号或车牌号），例如'我的 Toyota RAV4'");
        }

        log.warn("车辆信息护栏 — 输入不包含可识别的车辆关键词: {}",
                userInput.length() > 50 ? userInput.substring(0, 50) + "..." : userInput);
        return reject("VEHICLE_NOT_RECOGNIZED",
                "未识别到车辆信息，请提供品牌、型号或车牌号");
    }

    private ValidationResult pass() {
        return new ValidationResult(true, List.of());
    }

    private ValidationResult reject(String code, String message) {
        return new ValidationResult(false, List.of(
                new ValidationError(code, message, ValidationSeverity.ERROR)
        ));
    }
}
