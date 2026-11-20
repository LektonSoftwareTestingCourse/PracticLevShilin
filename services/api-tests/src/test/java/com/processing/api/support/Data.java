package com.processing.api.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Independent fixtures; all persisted state is prepared through public/internal HTTP APIs. */
public final class Data {
    private Data() {
    }

    public static String digits(int length) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < length; i++) {
            result.append(ThreadLocalRandom.current().nextInt(10));
        }
        return result.toString();
    }

    public static Map<String, Object> card() {
        return new LinkedHashMap<>(Map.of("bin", "400000", "cardholderName", "PRACTICE FIVE",
                "currencyCode", "643", "initialBalance", 1000000, "dailyLimit", 100000,
                "monthlyLimit", 500000));
    }

    public static Map<String, Object> authorization(String pan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mti", "0100");
        data.put("stan", digits(6));
        data.put("pan", pan);
        data.put("processingCode", "000000");
        data.put("amount", 100);
        data.put("currencyCode", "643");
        data.put("transmissionDateTime", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        data.put("terminalId", "T" + digits(7));
        data.put("terminalType", "POS");
        data.put("merchantId", "M" + digits(14));
        data.put("mcc", "5411");
        data.put("acquirerId", "ACQ001");
        data.put("issuerId", "ISS001");
        return data;
    }

    public static Map<String, Object> transaction() {
        Map<String, Object> data = authorization("400000" + digits(10));
        data.put("id", UUID.randomUUID().toString());
        data.put("createdAt", data.get("transmissionDateTime"));
        data.put("rrn", digits(12));
        data.put("status", "APPROVED");
        data.put("authCode", "TEST01");
        data.put("processingTimeMs", 10);
        data.put("acquiringFee", 0);
        return data;
    }
}
