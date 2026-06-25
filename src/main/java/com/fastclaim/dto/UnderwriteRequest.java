package com.fastclaim.dto;

import jakarta.validation.constraints.NotBlank;

public record UnderwriteRequest(
        @NotBlank(message = "用户ID不能为空")
        String userId,

        @NotBlank(message = "投保描述不能为空")
        String userInput
) {}
