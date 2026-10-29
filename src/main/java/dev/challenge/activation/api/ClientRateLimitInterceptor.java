package dev.challenge.activation.api;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class ClientRateLimitInterceptor implements HandlerInterceptor {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final int requestsPerMinute;
    private final Map<String, Bucket> bucketsByClient = new ConcurrentHashMap<>();

    ClientRateLimitInterceptor(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Principal client = request.getUserPrincipal();
        if (client == null) {
            return true;
        }
        ConsumptionProbe probe = bucketsByClient.computeIfAbsent(client.getName(), id -> newBucket())
                .tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw new RateLimitExceededException(roundUpToSeconds(probe.getNanosToWaitForRefill()));
        }
        return true;
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(requestsPerMinute).refillGreedy(requestsPerMinute, WINDOW))
                .build();
    }

    private static Duration roundUpToSeconds(long nanos) {
        return Duration.ofSeconds(Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L));
    }
}
