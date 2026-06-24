package com.fastclaim.dto;

import java.time.LocalDateTime;

public record ChatResponse(
        String response,
        String sessionId,
        boolean isNewSession,
        LocalDateTime timestamp
) {
}
