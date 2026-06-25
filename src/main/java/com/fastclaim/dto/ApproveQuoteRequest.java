package com.fastclaim.dto;

/**
 * overridePremiumAmount 可选，不传则保持原保费不变。
 */
public record ApproveQuoteRequest(
        Double overridePremiumAmount
) {}
