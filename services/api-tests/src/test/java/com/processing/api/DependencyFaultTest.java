package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/** Technical outcomes from AuthServiceImpl and the Logger exception handler. */
@Tag("fault")
class DependencyFaultTest {
    @BeforeAll
    static void replicasReady() {
        for (String service : List.of("authfault", "authreadonly", "loggerreadonly")) {
            await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                    .untilAsserted(() -> given().spec(Api.spec(service)).get("/actuator/health")
                            .then().statusCode(200).body("status", equalTo("UP")));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "01,503,96,SERVICE_UNAVAILABLE",
            "02,403,05,CARD_BLOCKED",
            "03,400,05,UNKNOWN_REASON",
            "05,503,96,RESERVATION_FAILED",
            "06,422,51,INSUFFICIENT_FUNDS",
            "07,404,14,CARD_NOT_FOUND",
            "08,404,14,CARD_NOT_FOUND",
            "09,503,96,SERVICE_UNAVAILABLE"
    })
    void authorizeMapsCardDependencyResponses(String suffix, int status, String code, String reason) {
        var request = Data.authorization("400000" + Data.digits(8) + suffix);
        Response result = Api.post("authfault", "/api/internal/authorize", request);
        declined(result, status, code, reason);
        result.then().body("stan", equalTo(request.get("stan"))).body("rrn", nullValue());
    }

    @ParameterizedTest
    @CsvSource({"05,ROLLBACK_FAILED", "09,SERVICE_UNAVAILABLE"})
    void rollbackMapsCardDependencyResponses(String suffix, String reason) {
        String pan = "400000" + Data.digits(8) + suffix;
        String rrn = Data.digits(12);
        Response result = Api.post("authfault", "/api/internal/rollback", Map.of("pan", pan, "rrn", rrn, "amount", 100));
        declined(result, 503, "96", reason);
        result.then().body("rrn", equalTo(rrn));
    }

    @Test
    void authorizationFailsWhenLimitUsageCannotBeWritten() {
        var request = Data.authorization("400000" + Data.digits(8) + "05");
        Response result = Api.post("authreadonly", "/api/internal/authorize", request);
        // D13: transaction completion throws UnexpectedRollbackException before the decline can reach the caller.
        result.then().statusCode(500).body("status", equalTo(500))
                .body("error", equalTo("Internal Server Error")).body("path", equalTo("/api/internal/authorize"))
                .body("responseCode", nullValue()).body("declineReason", nullValue());
    }

    @Test
    void loggerReturnsRetryable503WhenPersistenceFails() {
        var request = Data.transaction();
        Response result = Api.post("loggerreadonly", "/api/internal/log", request);
        Api.error(result, 503, "transaction-logger", "Transaction logger database is unavailable");
        result.then().body("error", equalTo("Database operation failed")).body("retryAfterMs", equalTo("1000"));
        Api.search(request.get("pan").toString()).then().statusCode(200).body("transactions", hasSize(0));
    }

    private static void declined(Response response, int status, String code, String reason) {
        response.then().statusCode(status).body("responseCode", equalTo(code))
                .body("status", equalTo("DECLINED")).body("declineReason", equalTo(reason))
                .body("processingTimeMs", greaterThanOrEqualTo(0));
    }
}
