package com.fastclaim.dto;

import jakarta.validation.constraints.NotNull;

public record PayRequest(
        @NotNull(message = "报价单ID不能为空")
        Long quoteId
) {}
