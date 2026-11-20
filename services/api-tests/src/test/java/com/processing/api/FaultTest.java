package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Real isolated services with deliberately unavailable dependencies; only HTTP observations. */
@Tag("fault")
class FaultTest {
    @BeforeAll
    static void ready() {
        for (String service : List.of("faultgateway", "unavailable")) {
            await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1))
                    .ignoreExceptions().untilAsserted(() -> given().spec(Api.spec(service))
                            .get("/actuator/health").then().statusCode(200).body("status", equalTo("UP")));
        }
    }

    @Test
    void failedPublishRollsBackAndNeverCreatesLog() {
        // given: only the extra Switch uses rabbitmq:1. No broker/container is stopped.
        var card = Api.card();
        var request = Data.authorization(card.get("pan").toString());
        // when
        var response = Api.post("faultgateway", "/api/transactions", request);
        // then: RouteService systemError after failed publish and successful compensation.
        response.then().statusCode(200).body("responseCode", equalTo("96"))
                .body("status", equalTo("DECLINED")).body("declineReason", equalTo("System error"))
                .body("rrn", nullValue()).body("authCode", nullValue());
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
                    given().spec(Api.spec("cards")).get("/api/cards/{pan}", card.get("pan"))
                            .then().statusCode(200).body("availableBalance", equalTo(1000000));
                    Api.search(card.get("pan").toString()).then().statusCode(200)
                            .body("total", equalTo(0)).body("transactions", hasSize(0));
                });
    }

    @Test
    void unavailableDownstreamAndRateLimit() {
        // given: dedicated Gateway, low token bucket and closed downstream port.
        var request = Data.authorization("400000" + Data.digits(10));
        // when
        var futures = IntStream.range(0, 8).mapToObj(i -> CompletableFuture.supplyAsync(() ->
                Api.post("unavailable", "/api/transactions", request))).toList();
        var responses = futures.stream().map(CompletableFuture::join).toList();
        // then
        var limited = responses.stream().filter(response -> response.statusCode() == 429).toList();
        var unavailable = responses.stream().filter(response -> response.statusCode() == 500).toList();
        assertFalse(limited.isEmpty(), "Dedicated rate limiter must reject a burst");
        assertFalse(unavailable.isEmpty(), "At least one request must reach the failed downstream");
        for (var response : limited) {
            response.then().statusCode(429).header("Retry-After", matchesPattern("[0-9]+"))
                    .body("error", equalTo("RATE_LIMIT_EXCEEDED")).body("retryAfterMs", equalTo(1000));
        }
        // D09: connection refused currently produces Spring's generic 500 instead of the documented 503.
        for (var response : unavailable) {
            response.then().statusCode(500).body("path", equalTo("/api/transactions"))
                    .body("error", equalTo("Internal Server Error"));
        }
    }
}
