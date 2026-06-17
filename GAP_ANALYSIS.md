# Gap Analysis — Client-Side Sharding

> Phân tích khoảng cách (gap) giữa hiện trạng project và mục tiêu trở thành một
> thư viện **client-side sharding** production-grade.
>
> *Client-side sharding* = logic định tuyến shard nằm trong ứng dụng (client),
> không qua proxy như Vitess / ShardingSphere-Proxy / Citus. Bản thân project này
> chính là một giải pháp client-side sharding; tài liệu này liệt kê những gì còn
> thiếu để nó đạt mức production-grade, kèm tham chiếu mã nguồn cụ thể.

Cập nhật: 2026-06-17

---

## Tóm tắt ưu tiên

| Mức | Gap | Vì sao quan trọng |
|---|---|---|
| 🔴 Critical | [#2 Đồng bộ topology đa node](#2-không-có-cơ-chế-đồng-bộ-topology-giữa-các-client-node) | Bản chất của client-side sharding; hiện gây sai dữ liệu khi scale-out |
| 🔴 Critical | [#1 Shard key chỉ hỗ trợ `long`](#1-shard-key-chỉ-hỗ-trợ-long) | Chặn shard theo uuid/string/tenant; chặn cả use case multi-tenant |
| 🟠 High | [#5 Silent fallback shard-0](#5-silent-fallback-to-shard-0-là-rủi-ro-đúng-đắn-dữ-liệu) | Rủi ro hỏng dữ liệu âm thầm |
| 🟠 High | [#4 Resilience / failover](#4-thiếu-resilience-trên-đường-định-tuyến) | Không chịu lỗi khi một shard down |
| 🟡 Medium | [#3 Strategy pluggable](#3-routing-strategy-không-thực-sự-pluggable), [#6 data-model](#6-thiếu-các-khái-niệm-data-model-cho-hệ-sharded), [#7 shared state](#7-trạng-thái-vận-hành-in-memory-migration--management) | Hoàn thiện linh hoạt & vận hành |
| 🟢 Low | [#8 reactive](#8-context-propagation-hạn-chế-ở-mô-hình-thread-per-request), [#9 secrets/observability](#9-các-gap-phụ-trợ) | Mở rộng phạm vi áp dụng |

---

## 1. Shard key chỉ hỗ trợ `long`

Toàn bộ pipeline định tuyến hard-code kiểu `long`:

- `ShardRouter.resolve(long shardKey)` — `sharding-core/.../ShardRouter.java:20`
- `ShardContext.set(long)` / `get():Long` — `sharding-core/.../ShardContext.java:42-52`
- `ShardKeyExtractor.extract(...) : long`, ép mọi giá trị về `long` qua `toLong()` — `sharding-aop/.../ShardKeyExtractor.java:116-122`
- `overrides: Map<Long, Integer>` — `sharding-autoconfigure/.../ShardProperties.java:46`

**Hệ quả thực tế:** không thể shard theo:

- `String` / `UUID` (rất phổ biến: tenant-id, user-uuid, email, order-no)
- Composite key (vd `tenant_id + region`)
- Kiểu nghiệp vụ khác (`BigDecimal`, `byte[]`, …)

Đây cũng là điểm chặn nếu mục tiêu là **shard theo client/tenant** (multi-tenant) vì
tenant ID thường là string/uuid. Hiện phải tự hash string → long thủ công ở từng call
site, dễ lệch logic giữa các nơi.

**Khuyến nghị:** generic hoá shard key (`resolve(Object key)` hoặc `<K>`), tách một SPI
`ShardKeyConverter` / `Hasher` để chuẩn hoá string/uuid/composite → vị trí trên ring một
cách nhất quán toàn hệ thống.

## 2. Không có cơ chế đồng bộ topology giữa các client node

Đây là điểm yếu cốt lõi nhất của *client*-side sharding mà project chưa giải quyết:

- Cấu hình shard đến từ YAML tĩnh hoặc `ShardManagementService.addShard()` qua Actuator
  (`ShardManagementEndpoint`), nhưng thay đổi đó **chỉ áp dụng cho đúng JVM instance gọi API**.
- Khi chạy nhiều instance ứng dụng (bắt buộc với client-side sharding ở production), mỗi
  node giữ một `ConsistentHashShardRouter` riêng trong bộ nhớ
  (`sharding-core/.../ConsistentHashShardRouter.java:28`). Add/remove shard trên một node
  → các node khác vẫn dùng ring cũ → **ghi/đọc lệch shard giữa các node → sai dữ liệu.**
- README tự thừa nhận: *"Online migration state hiện tại lưu in-memory — multi-node
  deployments cần shared store (Redis, DB)."*

**Đang thiếu:** một nguồn topology tập trung + cơ chế watch/propagate
(ZooKeeper / etcd / Consul / Redis / config table) để mọi client node thấy cùng một
version ring, kèm versioning/epoch để phát hiện node nào đang dùng topology cũ.

## 3. Routing strategy không thực sự pluggable

- Không có interface `ShardStrategy` chung. `HashShardStrategy` và
  `ConsistentHashShardStrategy` là hai class rời, signature khác nhau
  (`shardIndex(key, total)` tại `HashShardStrategy.java:17` vs `shardIndex(key)` tại
  `ConsistentHashShardStrategy.java:38`).
- `ShardProperties.Strategy` enum chỉ có `HASH | CONSISTENT_HASH`
  (`ShardProperties.java:101-106`).

**Thiếu các chiến lược phổ biến:**

- **Range-based** (theo khoảng thời gian/ID — quan trọng cho time-series, archiving).
- **Directory / lookup-table** (bảng ánh xạ key→shard; cần cho VIP/tenant tuỳ ý ở quy mô
  lớn — `overrides` map hiện chỉ là giải pháp in-memory cho vài key).
- **Geo / region-based**.
- Cho phép user cắm `ShardStrategy` custom qua bean.

## 4. Thiếu resilience trên đường định tuyến

- `RoutingDataSource.getTargetDataSource()` resolve shard rồi trả `dataSource()` trực
  tiếp — không health-check, retry, circuit breaker, hay failover sang replica khi primary
  chết (`sharding-jdbc/.../RoutingDataSource.java:46-57`).
- Read/write splitting có replica nhưng routing chỉ dựa vào cờ readOnly, không tự né
  replica đang lag/down.
- Scatter-gather có timeout (tốt — `ShardScatterGatherTemplate.java:50`) nhưng **một shard
  fail làm cả query fail** (`ExecutionException` rethrow tại
  `ShardScatterGatherTemplate.java:261-262`); chưa có chế độ partial-result / best-effort.

## 5. "Silent fallback to shard-0" là rủi ro đúng đắn dữ liệu

`RoutingDataSource` fallback về shard-0 khi không có shard key trong giai đoạn startup
(`ShardContext.isFallbackAllowed()`). Cơ chế `disableFallback()` sau `ApplicationReadyEvent`
giúp giảm thiểu, nhưng:

- Là **trạng thái process-wide tĩnh** (`volatile boolean fallbackAllowed` —
  `ShardContext.java:32`) — fragile trong test và môi trường nhiều context.
- Nếu listener `disableFallback()` không chạy, mọi write thiếu key âm thầm đổ vào shard-0.
  Đây là loại bug "im lặng làm hỏng dữ liệu"; nên có guard mạnh hơn (vd bắt buộc opt-in cho
  từng datasource startup-probe thay vì global flag).

## 6. Thiếu các khái niệm data-model cho hệ sharded

- **Broadcast / reference tables:** không có khái niệm bảng tra cứu nhỏ được replicate sang
  mọi shard (currency, country, config…). Hiện mọi join với bảng đó phải scatter-gather.
- **Binding / co-location tables:** không có ràng buộc đảm bảo các entity liên quan
  (account + transaction) cùng shard key được co-locate; hoàn toàn dựa vào kỷ luật lập trình.
- **Distributed ID generation:** không có sinh ID toàn cục (Snowflake-style) — client-side
  sharding gần như luôn cần PK không đụng độ giữa shard, lý tưởng là encode được shard.

## 7. Trạng thái vận hành in-memory (migration & management)

- `ShardMigrationService` lưu state migration in-memory; multi-node không nhất quán
  (README xác nhận).
- `ShardManagementService` thay đổi router in-memory.

→ Liên quan trực tiếp [gap #2](#2-không-có-cơ-chế-đồng-bộ-topology-giữa-các-client-node):
cần shared store + coordination để các thao tác topology/migration nhất quán toàn cụm client.

## 8. Context propagation hạn chế ở mô hình thread-per-request

- `ShardContext` dựa trên `ThreadLocal`. Có decorator cho `@Async`
  (`ShardContextTaskDecorator`), nhưng:
  - **Không hỗ trợ reactive / WebFlux** (không tích hợp Reactor `Context`).
  - Không tích hợp `micrometer-context-propagation` (`ThreadLocalAccessor`) — dễ vỡ khi
    dùng chung virtual threads + structured concurrency hoặc các thư viện propagate context khác.

## 9. Các gap phụ trợ

- **Cross-shard transaction:** chỉ single-shard (by design). Saga (v4) có bù trừ nhưng không
  phải distributed ACID — chấp nhận được, nhưng cần nêu rõ yêu cầu idempotency cho client.
- **Bảo mật cấu hình:** password shard để plaintext trong YAML/`DataSourceConfig`
  (`ShardProperties.java:226`); thiếu tích hợp secret manager / mã hoá.
- **Quan sát rebalancing:** khi add/remove shard trên consistent hash ring, không có công cụ
  tính/đo "key nào bị remap" để chủ động warm cache / invalidate (CDC module có invalidator
  nhưng không gắn với sự kiện đổi topology).

---

## Đề xuất lộ trình

1. **Generic shard key** (gap #1) — nền tảng, mở khoá multi-tenant & string/uuid sharding.
2. **Distributed topology coordination** (gap #2, #7) — điều kiện cần để chạy multi-node an toàn.
3. **Routing resilience** (gap #4) + siết **fallback safety** (gap #5).
4. **Strategy SPI + range/directory strategies** (gap #3) và **data-model primitives** (gap #6).
5. **Reactive propagation & secrets** (gap #8, #9).
