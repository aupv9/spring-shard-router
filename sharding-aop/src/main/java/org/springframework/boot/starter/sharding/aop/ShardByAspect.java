package org.springframework.boot.starter.sharding.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.boot.starter.sharding.jpa.ShardBy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * AOP aspect that intercepts methods annotated with {@link ShardBy} and automatically
 * sets the {@link ShardContext} before execution, then clears it in a finally block.
 *
 * <p>Ordered at {@code HIGHEST_PRECEDENCE + 10} so it runs <em>before</em>
 * {@code @Transactional} advice, ensuring the shard key is in context when
 * Spring's transaction manager acquires a connection.
 *
 * <p>Usage example:
 * <pre>{@code
 * @Service
 * public class PaymentService {
 *
 *     // Uses the first long parameter as shard key
 *     @ShardBy
 *     public void processPayment(long accountId, BigDecimal amount) { ... }
 *
 *     // Uses the parameter named "accountId"
 *     @ShardBy("accountId")
 *     public Balance getBalance(long accountId) { ... }
 *
 *     // Extracts "accountId" field from the Payment entity
 *     @ShardBy(value = "accountId", fromEntity = true)
 *     public void createPayment(Payment payment) { ... }
 * }
 * }</pre>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ShardByAspect {

    @Around("@annotation(shardBy)")
    public Object aroundShardBy(ProceedingJoinPoint pjp, ShardBy shardBy) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Object[] args = pjp.getArgs();

        long shardKey = ShardKeyExtractor.extract(method, args, shardBy);

        try {
            ShardContext.set(shardKey);
            return pjp.proceed();
        } finally {
            ShardContext.clear();
        }
    }
}
