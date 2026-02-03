# ADR-006: Selective Retry Policy

## Status
Accepted

## Context
When TfL API calls fail, we need to decide which errors warrant a retry attempt. Blindly retrying all errors wastes resources and can make problems worse (e.g., hammering an API that's rejecting our malformed requests).

## Decision
Implement selective retry that only retries **transient** errors:

### Retryable (transient errors)
| Status | Meaning | Rationale |
|--------|---------|-----------|
| 408 | Request Timeout | Server didn't respond in time - transient |
| 429 | Too Many Requests | Rate limited - will clear with backoff |
| 5xx | Server Error | Server-side issue - likely transient |
| IOException | Network Error | Connection issues - likely transient |

### Not Retryable (permanent errors)
| Status | Meaning | Rationale |
|--------|---------|-----------|
| 400 | Bad Request | Our bug - retrying won't help |
| 401 | Unauthorized | Auth misconfiguration - needs fix |
| 403 | Forbidden | Permission issue - needs investigation |
| 404 | Not Found | Resource doesn't exist |
| 405+ | Other 4xx | Client errors - our problem |

## Implementation
```java
private boolean isRetryableStatus(int status) {
    return status == 408    // Request Timeout
        || status == 429    // Too Many Requests
        || status >= 500;   // Server errors
}

private boolean isRetryableException(Throwable t) {
    if (t instanceof HttpStatusException httpEx) {
        return httpEx.isRetryable();
    }
    // Network errors (IOException) are always retryable
    return t instanceof java.io.IOException;
}
```

## Consequences

### Positive
- **Resource efficiency**: Don't waste retries on unrecoverable errors
- **Faster failure**: 4xx errors fail immediately instead of waiting through retries
- **Clearer debugging**: Logs distinguish between "gave up after retries" vs "failed immediately"
- **API politeness**: Don't hammer TfL with requests they're rejecting

### Negative
- **Theoretical edge case**: If TfL returns incorrect 4xx (e.g., 400 for rate limiting), we won't retry
  - Mitigation: This is rare for mature APIs; circuit breaker still protects us

### Interaction with Circuit Breaker
- **All failures** (retryable or not) count toward circuit breaker threshold
- A flood of 4xx errors will still trip the circuit breaker
- This is intentional: consistent 4xx suggests a systemic problem worth investigating

## Alternatives Considered

### 1. Retry all errors
- Rejected: Wastes resources, can worsen rate limiting

### 2. Never retry
- Rejected: Loses resilience against transient network issues

### 3. Retry on specific TfL error codes
- Rejected: Too coupled to TfL implementation details

## References
- [HTTP Status Codes](https://developer.mozilla.org/en-US/docs/Web/HTTP/Status)
- [Retry Pattern - Azure Architecture](https://docs.microsoft.com/en-us/azure/architecture/patterns/retry)
