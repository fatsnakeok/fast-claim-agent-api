package com.fastclaim.dto;

/**
 * LLM 从自然语言中提取的结构化车辆信息。
 * EXTRACTION_FAILED 哨兵用于快速失败路径。
 */
public record VehicleInfo(
        String brand,
        String model,
        String licensePlate
) {
    public static final VehicleInfo EXTRACTION_FAILED = new VehicleInfo("", "", null);

    public boolean isFailed() {
        return (brand == null || brand.isEmpty()) && (model == null || model.isEmpty());
    }
}
