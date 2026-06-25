package com.fastclaim.dto;

public record ApproveQuoteResponse(
        Long quoteId,
        String status,
        double premiumAmount,
        String message
) {}
