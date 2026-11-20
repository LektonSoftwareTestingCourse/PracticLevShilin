package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import com.processing.api.support.Pairwise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Sources: DashboardController, ChartsFilter, TransactionRepository aggregation. */
class DashboardTest {
    @Test
    void statsHaveConsistentCounts() {
        // given
        var request = Data.transaction();
        Api.post("logger", "/api/internal/log", request).then().statusCode(201).body("status", equalTo("stored"));
        // when
        var response = given().spec(Api.spec("gateway")).get("/api/dashboard/stats");
        // then
        response.then().statusCode(200).body("totalTransactions", greaterThanOrEqualTo(1))
                .body("approvedCount", greaterThanOrEqualTo(1)).body("declinedCount", greaterThanOrEqualTo(0))
                .body("approvalRate", allOf(greaterThanOrEqualTo(0f), lessThanOrEqualTo(1f)))
                .body("totalAmount", notNullValue()).body("averageAmount", notNullValue())
                .body("avgProcessingTimeMs", notNullValue()).body("transactionsPerMinute", greaterThanOrEqualTo(0));
        assertEquals(response.jsonPath().getLong("totalTransactions"),
                response.jsonPath().getLong("approvedCount") + response.jsonPath().getLong("declinedCount"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 500})
    void recentLimit(int limit) {
        // given / when
        var response = given().spec(Api.spec("gateway")).queryParam("limit", limit).get("/api/dashboard/recent");
        // then
        response.then().statusCode(200).body("size()", lessThanOrEqualTo(limit))
                .body("status", everyItem(oneOf("APPROVED", "DECLINED")));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 501})
    void invalidRecentLimit(int limit) {
        // given / when
        var response = given().spec(Api.spec("gateway")).queryParam("limit", limit).get("/api/dashboard/recent");
        // then
        Api.error(response, 400, "transaction-logger", "limit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hour", "day"})
    void chartsEmptyHalfOpenRange(String granularity) {
        // given / when
        var response = given().spec(Api.spec("gateway")).queryParam("granularity", granularity)
                .queryParam("from", "2100-01-01T00:00:00Z").queryParam("to", "2100-01-01T00:00:00Z")
                .get("/api/dashboard/charts");
        // then
        response.then().statusCode(200).body("", hasSize(0));
    }

    @Test
    void unsupportedGranularity() {
        // given / when
        var response = given().spec(Api.spec("gateway")).queryParam("granularity", "minute")
                .get("/api/dashboard/charts");
        // then
        Api.error(response, 400, "transaction-logger", "granularity must be hour or day");
    }

    @Test
    void chartBoundsAndGranularityCoverEveryPairOfClasses() {
        Instant created = Instant.now().plus(50, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        var transaction = Data.transaction();
        transaction.put("createdAt", created.toString());
        Api.post("logger", "/api/internal/log", transaction).then().statusCode(201)
                .body("id", equalTo(transaction.get("id")));
        var options = List.of(List.of("granularity", "hour", "day"),
                List.of("from", created.toString(), created.plusSeconds(2).toString()),
                List.of("to", created.plusSeconds(1).toString(), created.toString()));
        for (int[] row : Pairwise.rows(options.size())) {
            var query = new LinkedHashMap<String, String>();
            for (int i = 0; i < row.length; i++) {
                if (row[i] != 0) {
                    query.put(options.get(i).getFirst(), options.get(i).get(row[i]));
                }
            }
            var result = given().spec(Api.spec("gateway")).queryParams(query).get("/api/dashboard/charts");
            if (row[1] == 2 && row[2] != 0) {
                result.then().statusCode(400).body("serviceName", equalTo("transaction-logger"));
            } else {
                var bucket = created.truncatedTo(row[0] == 2 ? ChronoUnit.DAYS : ChronoUnit.HOURS).toString();
                result.then().statusCode(200).body("timestamp", notNullValue());
                if (row[1] != 2 && row[2] != 2) {
                    result.then().body("timestamp", hasItem(bucket));
                    if (row[1] == 1 && row[2] == 1) {
                        result.then().body("", hasSize(1)).body("[0].amount", equalTo(100));
                    }
                } else if (row[1] == 1 && row[2] == 2) {
                    result.then().body("", hasSize(0));
                }
            }
        }
    }
}
