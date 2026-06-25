package com.fastclaim.agent;

import com.fastclaim.dto.UnderwriteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UnderwritingAgentTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ============================================================
    // US-U1：低风险用户自动批准
    // ============================================================

    @Test
    void lowRiskUserShouldGetApproved() {
        UnderwriteRequest request = new UnderwriteRequest(
                "low-risk-user", "我想给我的 Toyota RAV4 上保险，车牌 LOW001");
        HttpEntity<UnderwriteRequest> entity = new HttpEntity<>(request);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/insurance/underwrite"), entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("APPROVED");
        assertThat(response.getBody()).contains("4800.0");
    }

    // ============================================================
    // US-U2：中风险用户转人工
    // ============================================================

    @Test
    void mediumRiskUserShouldBeReferred() {
        UnderwriteRequest request = new UnderwriteRequest(
                "medium-risk-user", "给我的 Honda Civic 上保险");
        HttpEntity<UnderwriteRequest> entity = new HttpEntity<>(request);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/insurance/underwrite"), entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("REFERRED");
        assertThat(response.getBody()).contains("3600.0");
    }

    // ============================================================
    // US-U3：高风险用户自动拒绝
    // ============================================================

    @Test
    void highRiskUserShouldBeDeclined() {
        UnderwriteRequest request = new UnderwriteRequest(
                "high-risk-user", "我想给我的 BMW X5 上保险，车牌 HIGH001");
        HttpEntity<UnderwriteRequest> entity = new HttpEntity<>(request);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/insurance/underwrite"), entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("DECLINED");
        assertThat(response.getBody()).contains("18000.0");
    }

    // ============================================================
    // US-U4：查询保单列表 / 详情
    // ============================================================

    @Test
    void shouldQueryPoliciesByUserId() {
        HttpEntity<Void> entity = new HttpEntity<>(new HttpHeaders());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/insurance/policies?userId=low-risk-user"),
                HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldReturn404ForUnknownPolicyNumber() {
        HttpEntity<Void> entity = new HttpEntity<>(new HttpHeaders());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/insurance/policies/POL-NONEXISTENT"),
                HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ============================================================
    // 错误路径
    // ============================================================

    @Test
    void unknownCustomerShouldReturn422() {
        UnderwriteRequest request = new UnderwriteRequest(
                "unknown-user", "我想给我的 Toyota RAV4 上保险");
        HttpEntity<UnderwriteRequest> entity = new HttpEntity<>(request);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/insurance/underwrite"), entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("ERROR");
    }

    @Test
    void noVehicleInfoShouldReturn422() {
        UnderwriteRequest request = new UnderwriteRequest(
                "low-risk-user", "你好，天气不错");
        HttpEntity<UnderwriteRequest> entity = new HttpEntity<>(request);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/insurance/underwrite"), entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ============================================================
    // 健康检查
    // ============================================================

    @Test
    void healthEndpointShouldReturnOk() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/insurance/health"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("OK");
    }
}
