package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import com.processing.api.support.Pairwise;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Three equivalence classes for each search argument, with every pair of levels exercised. */
class SearchPairwiseTest {
    @Test
    void everyPairOfFilterAndPaginationValues() {
        Instant order = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        var day = LocalDate.ofInstant(order, ZoneOffset.UTC);
        var target = Data.transaction();
        target.put("createdAt", order.toString());
        target.put("transmissionDateTime", day + "T12:00:00Z");
        target.put("status", "DECLINED");
        String reason = "pairwise" + UUID.randomUUID().toString().replace("-", "");
        target.put("declineReason", reason);
        var next = new LinkedHashMap<>(target);
        next.put("id", UUID.randomUUID().toString());
        next.put("createdAt", order.minusMillis(1).toString());
        for (var tx : List.of(target, next)) {
            Api.post("logger", "/api/internal/log", tx).then().statusCode(201).body("id", equalTo(tx.get("id")));
        }
        var factors = List.of(
                new Factor("pan", target.get("pan"), "999999" + Data.digits(10)),
                new Factor("status", "DECLINED", "APPROVED"),
                new Factor("merchantId", target.get("merchantId"), "M" + Data.digits(14)),
                new Factor("issuerId", target.get("issuerId"), "ISS999"),
                new Factor("mcc", target.get("mcc"), "0000"),
                new Factor("declineReason", reason, reason + "other"),
                new Factor("dateFrom", day.toString(), day.plusDays(1).toString()),
                new Factor("dateTo", day.toString(), day.minusDays(1).toString()),
                new Factor("limit", 1, 500),
                new Factor("offset", 0, 1));
        var rows = Pairwise.rows(factors.size());
        for (int[] row : rows) {
            var query = new LinkedHashMap<String, Object>();
            for (int i = 0; i < factors.size(); i++) {
                if (row[i] != 0) {
                    var factor = factors.get(i);
                    query.put(factor.name(), row[i] == 1 ? factor.first() : factor.second());
                }
            }
            var result = given().spec(Api.spec("gateway")).queryParams(query).get("/api/transactions/search");
            boolean reversed = row[6] != 0 && row[7] != 0 && (row[6] == 2 || row[7] == 2);
            if (reversed) {
                result.then().statusCode(400).body("serviceName", equalTo("transaction-logger"));
                continue;
            }
            boolean matches = java.util.Arrays.stream(row, 0, 8).noneMatch(level -> level == 2);
            boolean advanced = row[9] == 2;
            result.then().statusCode(200)
                    .body("transactions.id", matches && !advanced ? hasItem(target.get("id")) : not(hasItem(target.get("id"))))
                    .body("transactions.id", matches && (advanced || row[8] != 1)
                            ? hasItem(next.get("id")) : not(hasItem(next.get("id"))));
            if (row[8] == 1) {
                result.then().body("transactions", hasSize(lessThanOrEqualTo(1)));
            }
        }
    }

    private record Factor(String name, Object first, Object second) { }
}
