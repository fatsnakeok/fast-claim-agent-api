package com.fastclaim.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.common.StuckHandler;
import com.embabel.agent.api.common.StuckHandlerResult;
import com.embabel.agent.core.AgentProcess;
import com.fastclaim.dto.UnderwritingInput;
import com.fastclaim.dto.UnderwritingResult;
import com.fastclaim.dto.VehicleInfo;
import com.fastclaim.entity.Customer;
import com.fastclaim.entity.Quote;
import com.fastclaim.entity.Vehicle;
import com.fastclaim.entity.enums.QuoteStatus;
import com.fastclaim.repository.QuoteRepository;
import com.fastclaim.service.DataService;
import com.fastclaim.service.LlmSelectionService;
import com.fastclaim.service.PremiumCalculationService;
import com.fastclaim.service.RiskCalculationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

@Agent(description = "核保 Agent", planner = PlannerType.UTILITY)
public class UnderwritingAgent implements StuckHandler {

    private static final Logger log = LoggerFactory.getLogger(UnderwritingAgent.class);

    private final LlmSelectionService llmService;
    private final DataService dataService;
    private final RiskCalculationService riskCalcService;
    private final PremiumCalculationService premiumCalcService;
    private final QuoteRepository quoteRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UnderwritingAgent(LlmSelectionService llmService, DataService dataService,
                             RiskCalculationService riskCalcService,
                             PremiumCalculationService premiumCalcService,
                             QuoteRepository quoteRepository) {
        this.llmService = llmService;
        this.dataService = dataService;
        this.riskCalcService = riskCalcService;
        this.premiumCalcService = premiumCalcService;
        this.quoteRepository = quoteRepository;
    }

    /**
     * [1] LLM 从自然语言中提取结构化车辆信息
     */
    @Action
    public VehicleInfo extractVehicleInfo(UnderwritingInput input, OperationContext context) {
        log.debug("extractVehicleInfo — 调用 LLM 提取车辆信息, userId: {}", input.userId());

        String jsonResult = context.ai()
                .withLlm(llmService.forSimpleQuery())
                .generateText("""
                        你是一个车辆信息提取工具。从用户输入中提取车辆的品牌、型号和车牌号。
                        以 JSON 格式返回：{"brand":"...","model":"...","licensePlate":"..."}
                        如果无法识别任何车辆信息，返回：{"brand":"","model":"","licensePlate":null}

                        用户输入：""" + input.message());

        try {
            String json = extractJson(jsonResult);
            VehicleInfo info = objectMapper.readValue(json, VehicleInfo.class);
            log.debug("extractVehicleInfo — LLM 提取结果: brand={}, model={}, plate={}",
                    info.brand(), info.model(), info.licensePlate());
            if (info.isFailed()) {
                context.bind("underwriting_error", "无法从用户输入中识别车辆信息");
            }
            return info;
        } catch (JsonProcessingException e) {
            log.error("extractVehicleInfo — LLM 返回 JSON 解析失败: {}", jsonResult, e);
            context.bind("underwriting_error", "车辆信息提取失败");
            return VehicleInfo.EXTRACTION_FAILED;
        }
    }

    /**
     * [2] 从数据库查找客户，未找到返回 sentinel
     */
    @Action
    public Customer lookupCustomer(UnderwritingInput input, OperationContext context) {
        String userId = input.userId();
        log.debug("lookupCustomer — userId: {}", userId);
        Customer customer = dataService.findCustomer(userId);
        if (Customer.isLookupFailed(customer)) {
            context.bind("underwriting_error", "客户不存在: " + userId);
        }
        return customer;
    }

    /**
     * [3] 三级优先级查找车辆，未找到返回 sentinel
     */
    @Action
    public Vehicle lookupVehicle(VehicleInfo vehicleInfo, Customer customer,
                                  OperationContext context) {
        if (Customer.isLookupFailed(customer)) {
            log.debug("lookupVehicle — 客户为 sentinel，跳过车辆查找");
            return Vehicle.lookupFailed();
        }
        if (vehicleInfo.isFailed()) {
            log.warn("lookupVehicle — 车辆信息为 EXTRACTION_FAILED，返回 sentinel");
            context.bind("underwriting_error", "车辆信息提取失败，无法查找车辆");
            return Vehicle.lookupFailed();
        }
        Vehicle vehicle = dataService.findVehicle(vehicleInfo, customer);
        if (Vehicle.isLookupFailed(vehicle)) {
            context.bind("underwriting_error",
                    "车辆未找到 — brand: " + vehicleInfo.brand()
                            + ", model: " + vehicleInfo.model()
                            + ", plate: " + vehicleInfo.licensePlate());
        }
        return vehicle;
    }

    /**
     * [4] 风险评估 — 入口检查 sentinel，按阈值路由到 @State 子类型
     */
    @Action
    public UnderwritingDecision assessRisk(Customer customer, Vehicle vehicle,
                                            OperationContext context) {
        if (Customer.isLookupFailed(customer)) {
            log.warn("assessRisk — 路由到 CustomerNotFound");
            String errorMsg = getErrorFromContext(context);
            return new CustomerNotFound(errorMsg);
        }
        if (Vehicle.isLookupFailed(vehicle)) {
            log.warn("assessRisk — 路由到 VehicleLookupError");
            String errorMsg = getErrorFromContext(context);
            return new VehicleLookupError(errorMsg);
        }

        String errorMsg = getErrorFromContext(context);
        if (errorMsg != null && !errorMsg.isEmpty()) {
            log.warn("assessRisk — 前置错误，路由到 ExtractionFailed: {}", errorMsg);
            return new ExtractionFailed(errorMsg);
        }

        double riskScore = riskCalcService.calculate(customer, vehicle);
        double premium = premiumCalcService.calculate(
                vehicle.getVehicleValue(), riskScore, "COMPREHENSIVE");

        if (riskCalcService.isLowRisk(riskScore)) {
            log.info("assessRisk — 低风险 APPROVED: score={}, premium={}", riskScore, premium);
            return new LowRiskQuote(customer, vehicle, riskScore, premium);
        } else if (riskCalcService.isHighRisk(riskScore)) {
            log.info("assessRisk — 高风险 DECLINED: score={}, premium={}", riskScore, premium);
            return new HighRiskDecline(customer, vehicle, riskScore, premium);
        } else {
            log.info("assessRisk — 中风险 REFERRED: score={}, premium={}", riskScore, premium);
            return new MediumRiskReview(customer, vehicle, riskScore, premium);
        }
    }

    private String getErrorFromContext(OperationContext context) {
        Object err = context.get("underwriting_error");
        return err instanceof String s ? s : null;
    }

    /**
     * 从 LLM 返回内容中提取 JSON — 处理 markdown 代码块包裹
     */
    private String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf("{");
            int end = trimmed.lastIndexOf("}");
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        int start = trimmed.indexOf("{");
        int end = trimmed.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    // ============================================================
    // @State 路由 — sealed interface + 6 个子类型
    //   permits 的作用是显式限定哪些类可以实现该接口
    // 含义：UnderwritingDecision 只允许这 6 个 record 实现它，其他任何类都不行。
    // ============================================================

    @State
    public sealed interface UnderwritingDecision permits
            LowRiskQuote, MediumRiskReview, HighRiskDecline,
            CustomerNotFound, VehicleLookupError, ExtractionFailed {
    }

    // --- 正常路径 ---

    public record LowRiskQuote(Customer customer, Vehicle vehicle,
                                double riskScore, double premium) implements UnderwritingDecision {
        @Action
        @AchievesGoal(description = "自动批准低风险报价")
        public UnderwritingResult execute(@Provided QuoteRepository quoteRepo) {
            Quote quote = buildAndSaveQuote(customer, vehicle, riskScore, premium,
                    QuoteStatus.APPROVED, null, quoteRepo);
            log.info("核保通过 — quoteId: {}, riskScore: {}, premium: {}", quote.getId(), riskScore, premium);
            return UnderwritingResult.of(quote.getId(), "APPROVED", riskScore, premium,
                    "核保通过。保费 ¥" + String.format("%.2f", premium) + "，报价单有效期 30 天。");
        }
    }

    public record MediumRiskReview(Customer customer, Vehicle vehicle,
                                    double riskScore, double premium) implements UnderwritingDecision {
        @Action
        @AchievesGoal(description = "转人工审核中风险报价")
        public UnderwritingResult execute(@Provided QuoteRepository quoteRepo) {
            Quote quote = buildAndSaveQuote(customer, vehicle, riskScore, premium,
                    QuoteStatus.REFERRED, null, quoteRepo);
            log.info("核保转人工 — quoteId: {}, riskScore: {}, premium: {}", quote.getId(), riskScore, premium);
            return UnderwritingResult.of(quote.getId(), "REFERRED", riskScore, premium,
                    "需要人工审核。您的申请已转交核保员处理。");
        }
    }

    public record HighRiskDecline(Customer customer, Vehicle vehicle,
                                   double riskScore, double premium) implements UnderwritingDecision {
        @Action
        @AchievesGoal(description = "自动拒绝高风险报价")
        public UnderwritingResult execute(@Provided QuoteRepository quoteRepo) {
            String reason = buildRejectionReason();
            Quote quote = buildAndSaveQuote(customer, vehicle, riskScore, premium,
                    QuoteStatus.DECLINED, reason, quoteRepo);
            log.info("核保拒绝 — quoteId: {}, riskScore: {}, reason: {}", quote.getId(), riskScore, reason);
            return UnderwritingResult.of(quote.getId(), "DECLINED", riskScore, premium,
                    "核保不通过：风险评分过高。" + reason);
        }

        private String buildRejectionReason() {
            int age = customer.getAge();
            StringBuilder sb = new StringBuilder();
            sb.append("年龄").append(age).append("岁");
            if (customer.getDrivingExperienceYears() < 3) {
                sb.append("(年轻驾驶员)");
            }
            sb.append("+驾龄").append(customer.getDrivingExperienceYears()).append("年");
            sb.append("+").append(customer.getAccidentCount()).append("次历史事故");
            int vehicleAge = LocalDateTime.now().getYear() - vehicle.getYear();
            sb.append("+").append(vehicleAge).append("年车龄旧车");
            if (vehicle.getVehicleValue() > 500000) {
                sb.append("+高价值车型");
            }
            return sb.toString();
        }
    }

    // --- 错误路径 ---

    public record CustomerNotFound(String errorMsg) implements UnderwritingDecision {
        @Action
        @AchievesGoal(description = "客户不存在错误")
        public UnderwritingResult execute() {
            log.warn("核保失败 — 客户不存在: {}", errorMsg);
            return UnderwritingResult.error("客户不存在: " + errorMsg);
        }
    }

    public record VehicleLookupError(String errorMsg) implements UnderwritingDecision {
        @Action
        @AchievesGoal(description = "车辆未找到错误")
        public UnderwritingResult execute() {
            log.warn("核保失败 — 车辆未找到: {}", errorMsg);
            return UnderwritingResult.error("车辆未找到: " + errorMsg);
        }
    }

    public record ExtractionFailed(String errorMsg) implements UnderwritingDecision {
        @Action
        @AchievesGoal(description = "LLM 提取失败错误")
        public UnderwritingResult execute() {
            log.warn("核保失败 — LLM 提取失败: {}", errorMsg);
            return UnderwritingResult.error("车辆信息提取失败: " + errorMsg);
        }
    }

    // --- 工具方法 ---

    private static Quote buildAndSaveQuote(Customer customer, Vehicle vehicle, double riskScore,
                                            double premium, QuoteStatus status,
                                            String rejectionReason, QuoteRepository repo) {
        Quote quote = new Quote();
        quote.setCustomer(customer);
        quote.setVehicle(vehicle);
        quote.setRiskScore(riskScore);
        quote.setPremiumAmount(premium);
        quote.setStatus(status);
        quote.setCoverageType("COMPREHENSIVE");
        if (rejectionReason != null) {
            quote.setRejectionReason(rejectionReason);
        }
        return repo.save(quote);
    }

    @Override
    public StuckHandlerResult handleStuck(AgentProcess agentProcess) {
        log.warn("UnderwritingAgent 超时 STUCK — AgentProcess: {}", agentProcess);
        return new StuckHandlerResult(
                "UnderwritingAgent stuck — no resolution available",
                this,
                com.embabel.agent.api.common.StuckHandlingResultCode.NO_RESOLUTION,
                agentProcess
        );
    }
}
