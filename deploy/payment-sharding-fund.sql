-- ============================================================================
-- payment-pro I-5b 资金域分库分表（payment / accounting / trade）：分片表 + 支撑表初始化
--
-- 方案选型：**单库分表**（与 I-5a 试点同构），不新增 pp_payment_0..3 / pp_accounting_0..3 /
--   pp_trade_0..3 库。理由（资金安全优先）：
--   ① 幂等表 idempotent_record、TCC 分支日志 tcc_branch_log、协调器 dtx_*、事务 outbox
--      与业务写仍在**同一库同一本地事务**，五层幂等 / 终态+outbox 原子性 / 参与者分支幂等全部不变；
--   ② 跨账户转账落多片时仍是**同一 MySQL 连接的同一本地事务**，原子性强于多库方案；
--   ③ 不新建库、不动连接串与权限，回滚只切 DataSource，风险面最小。
--
-- 分片键：
--   pp_payment.payment          按 id（即 paymentId）      CRC32 % 4
--   pp_payment.refund_record    按 payment_id            CRC32 % 4
--   pp_accounting.account_balance 按 account_id          CRC32 % 4  ← 同账户同片，片内余额/冻结一致
--   pp_accounting.ledger_entry    按 account_id          CRC32 % 4  ← 排序加锁/单账户流水序在片内成立
--   pp_trade.trade_order          按 merchant_id         CRC32 % 4  ← (merchant_id,out_trade_no) 首列即分片键
--
-- **绝不碎片**（协调器单库保证终态 + outbox 原子）：
--   dtx_transaction / dtx_branch / outbox / idempotent_record / sequences / shedlock /
--   amount_review / tcc_branch_log / transfer_record / reserve_check_log / reserve_bank_adjust
--
-- 跨片全局幂等（§0.5 E-7）：**独立索引表，不改 ID 形态**
--   payment_request_index(request_id PK, payment_id, shard, created_at)   单库不分片
--   refund_request_index (request_id PK, refund_id, payment_id, shard, created_at) 单库不分片
--   受理时先 INSERT 索引占位再写业务单，两步同一本地事务；索引主键即全局幂等闸门。
--   paymentId 仍为 P+十进制数字（分片态数字来自雪花），**不嵌基因、不改前缀/长度**，
--   ride-pro 存量 id 与 8 个冻结 OpenAPI 方法完全兼容。
--
-- 数据保护红线（本文件严格遵守）：
--   * 只 CREATE TABLE IF NOT EXISTS 新建分片表/支撑表；
--   * 既有行只 INSERT IGNORE 复制（additive），原表原行全部保留；
--   * 绝不 DROP / TRUNCATE / DELETE 任何既有业务行；
--   * 可变行（余额/状态）的逐列一致由应用内 ShardRowMirror 全行双写 + 启动同步保障，
--     本文件的 INSERT IGNORE 只做「缺行补齐」，不覆盖既有行；
--   * §5.1 步骤⑥「下线单库」**不做** —— 单库永久保留以支持一键回滚。
--
-- 分片算法：CRC32(分片键) % 4（MySQL CRC32 与 Java java.util.zip.CRC32 同为 IEEE CRC-32，
--          故本文件的 SQL 复制与应用内 ShardingSphere 路由算法逐行一致）。
--
-- 执行方式（幂等，可反复执行；应用启动时也会自动建表+同步，本文件是手工/审计通道）：
--   export PATH="$HOME/.docker/bin:$PATH"
--   set -a; . ~/.config/payment-pro/docker.env; set +a
--   docker exec -i -e PPDBPW="$MYSQL_PASSWORD" claim-shared-mysql \
--     sh -c 'mysql -uroot -p"$PPDBPW"' < deploy/payment-sharding-fund.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- pp_payment：payment_0..3（按 id）/ refund_record_0..3（按 payment_id）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pp_payment.payment_0 LIKE pp_payment.payment;
CREATE TABLE IF NOT EXISTS pp_payment.payment_1 LIKE pp_payment.payment;
CREATE TABLE IF NOT EXISTS pp_payment.payment_2 LIKE pp_payment.payment;
CREATE TABLE IF NOT EXISTS pp_payment.payment_3 LIKE pp_payment.payment;

INSERT IGNORE INTO pp_payment.payment_0 SELECT * FROM pp_payment.payment WHERE CRC32(id) % 4 = 0;
INSERT IGNORE INTO pp_payment.payment_1 SELECT * FROM pp_payment.payment WHERE CRC32(id) % 4 = 1;
INSERT IGNORE INTO pp_payment.payment_2 SELECT * FROM pp_payment.payment WHERE CRC32(id) % 4 = 2;
INSERT IGNORE INTO pp_payment.payment_3 SELECT * FROM pp_payment.payment WHERE CRC32(id) % 4 = 3;

CREATE TABLE IF NOT EXISTS pp_payment.refund_record_0 LIKE pp_payment.refund_record;
CREATE TABLE IF NOT EXISTS pp_payment.refund_record_1 LIKE pp_payment.refund_record;
CREATE TABLE IF NOT EXISTS pp_payment.refund_record_2 LIKE pp_payment.refund_record;
CREATE TABLE IF NOT EXISTS pp_payment.refund_record_3 LIKE pp_payment.refund_record;

INSERT IGNORE INTO pp_payment.refund_record_0 SELECT * FROM pp_payment.refund_record WHERE CRC32(payment_id) % 4 = 0;
INSERT IGNORE INTO pp_payment.refund_record_1 SELECT * FROM pp_payment.refund_record WHERE CRC32(payment_id) % 4 = 1;
INSERT IGNORE INTO pp_payment.refund_record_2 SELECT * FROM pp_payment.refund_record WHERE CRC32(payment_id) % 4 = 2;
INSERT IGNORE INTO pp_payment.refund_record_3 SELECT * FROM pp_payment.refund_record WHERE CRC32(payment_id) % 4 = 3;

-- 跨片全局幂等索引（单库不分片）+ 路由状态表
CREATE TABLE IF NOT EXISTS pp_payment.payment_request_index (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY,
    payment_id VARCHAR(32) NOT NULL,
    shard      INT         NOT NULL,
    created_at BIGINT      NOT NULL,
    INDEX idx_pri_payment (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pp_payment.refund_request_index (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY,
    refund_id  VARCHAR(32) NOT NULL,
    payment_id VARCHAR(32) NOT NULL,
    shard      INT         NOT NULL,
    created_at BIGINT      NOT NULL,
    INDEX idx_rri_refund (refund_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pp_payment.shard_route_state (
    domain     VARCHAR(64) NOT NULL PRIMARY KEY,
    active     BOOLEAN     NOT NULL,
    updated_at BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 索引回填（只 INSERT IGNORE，从原单表 + 各分片表取并集；绝不改写既有行）
INSERT IGNORE INTO pp_payment.payment_request_index(request_id, payment_id, shard, created_at)
    SELECT request_id, id, CRC32(id) % 4, created_at FROM pp_payment.payment;
INSERT IGNORE INTO pp_payment.payment_request_index(request_id, payment_id, shard, created_at)
    SELECT request_id, id, CRC32(id) % 4, created_at FROM pp_payment.payment_0;
INSERT IGNORE INTO pp_payment.payment_request_index(request_id, payment_id, shard, created_at)
    SELECT request_id, id, CRC32(id) % 4, created_at FROM pp_payment.payment_1;
INSERT IGNORE INTO pp_payment.payment_request_index(request_id, payment_id, shard, created_at)
    SELECT request_id, id, CRC32(id) % 4, created_at FROM pp_payment.payment_2;
INSERT IGNORE INTO pp_payment.payment_request_index(request_id, payment_id, shard, created_at)
    SELECT request_id, id, CRC32(id) % 4, created_at FROM pp_payment.payment_3;

INSERT IGNORE INTO pp_payment.refund_request_index(request_id, refund_id, payment_id, shard, created_at)
    SELECT request_id, id, payment_id, CRC32(payment_id) % 4, created_at FROM pp_payment.refund_record;
INSERT IGNORE INTO pp_payment.refund_request_index(request_id, refund_id, payment_id, shard, created_at)
    SELECT request_id, id, payment_id, CRC32(payment_id) % 4, created_at FROM pp_payment.refund_record_0;
INSERT IGNORE INTO pp_payment.refund_request_index(request_id, refund_id, payment_id, shard, created_at)
    SELECT request_id, id, payment_id, CRC32(payment_id) % 4, created_at FROM pp_payment.refund_record_1;
INSERT IGNORE INTO pp_payment.refund_request_index(request_id, refund_id, payment_id, shard, created_at)
    SELECT request_id, id, payment_id, CRC32(payment_id) % 4, created_at FROM pp_payment.refund_record_2;
INSERT IGNORE INTO pp_payment.refund_request_index(request_id, refund_id, payment_id, shard, created_at)
    SELECT request_id, id, payment_id, CRC32(payment_id) % 4, created_at FROM pp_payment.refund_record_3;

-- ---------------------------------------------------------------------------
-- pp_accounting：account_balance_0..3 / ledger_entry_0..3（均按 account_id）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pp_accounting.account_balance_0 LIKE pp_accounting.account_balance;
CREATE TABLE IF NOT EXISTS pp_accounting.account_balance_1 LIKE pp_accounting.account_balance;
CREATE TABLE IF NOT EXISTS pp_accounting.account_balance_2 LIKE pp_accounting.account_balance;
CREATE TABLE IF NOT EXISTS pp_accounting.account_balance_3 LIKE pp_accounting.account_balance;

INSERT IGNORE INTO pp_accounting.account_balance_0 SELECT * FROM pp_accounting.account_balance WHERE CRC32(account_id) % 4 = 0;
INSERT IGNORE INTO pp_accounting.account_balance_1 SELECT * FROM pp_accounting.account_balance WHERE CRC32(account_id) % 4 = 1;
INSERT IGNORE INTO pp_accounting.account_balance_2 SELECT * FROM pp_accounting.account_balance WHERE CRC32(account_id) % 4 = 2;
INSERT IGNORE INTO pp_accounting.account_balance_3 SELECT * FROM pp_accounting.account_balance WHERE CRC32(account_id) % 4 = 3;

CREATE TABLE IF NOT EXISTS pp_accounting.ledger_entry_0 LIKE pp_accounting.ledger_entry;
CREATE TABLE IF NOT EXISTS pp_accounting.ledger_entry_1 LIKE pp_accounting.ledger_entry;
CREATE TABLE IF NOT EXISTS pp_accounting.ledger_entry_2 LIKE pp_accounting.ledger_entry;
CREATE TABLE IF NOT EXISTS pp_accounting.ledger_entry_3 LIKE pp_accounting.ledger_entry;

INSERT IGNORE INTO pp_accounting.ledger_entry_0 SELECT * FROM pp_accounting.ledger_entry WHERE CRC32(account_id) % 4 = 0;
INSERT IGNORE INTO pp_accounting.ledger_entry_1 SELECT * FROM pp_accounting.ledger_entry WHERE CRC32(account_id) % 4 = 1;
INSERT IGNORE INTO pp_accounting.ledger_entry_2 SELECT * FROM pp_accounting.ledger_entry WHERE CRC32(account_id) % 4 = 2;
INSERT IGNORE INTO pp_accounting.ledger_entry_3 SELECT * FROM pp_accounting.ledger_entry WHERE CRC32(account_id) % 4 = 3;

CREATE TABLE IF NOT EXISTS pp_accounting.shard_route_state (
    domain     VARCHAR(64) NOT NULL PRIMARY KEY,
    active     BOOLEAN     NOT NULL,
    updated_at BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- pp_trade：trade_order_0..3（按 merchant_id）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pp_trade.trade_order_0 LIKE pp_trade.trade_order;
CREATE TABLE IF NOT EXISTS pp_trade.trade_order_1 LIKE pp_trade.trade_order;
CREATE TABLE IF NOT EXISTS pp_trade.trade_order_2 LIKE pp_trade.trade_order;
CREATE TABLE IF NOT EXISTS pp_trade.trade_order_3 LIKE pp_trade.trade_order;

INSERT IGNORE INTO pp_trade.trade_order_0 SELECT * FROM pp_trade.trade_order WHERE CRC32(merchant_id) % 4 = 0;
INSERT IGNORE INTO pp_trade.trade_order_1 SELECT * FROM pp_trade.trade_order WHERE CRC32(merchant_id) % 4 = 1;
INSERT IGNORE INTO pp_trade.trade_order_2 SELECT * FROM pp_trade.trade_order WHERE CRC32(merchant_id) % 4 = 2;
INSERT IGNORE INTO pp_trade.trade_order_3 SELECT * FROM pp_trade.trade_order WHERE CRC32(merchant_id) % 4 = 3;

CREATE TABLE IF NOT EXISTS pp_trade.shard_route_state (
    domain     VARCHAR(64) NOT NULL PRIMARY KEY,
    active     BOOLEAN     NOT NULL,
    updated_at BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 回滚一致性补偿：分片期新增行 additive 补回原单表（缺行补齐，不覆盖既有行）
-- 可变行的逐列一致由应用内 ShardRowMirror 全行双写 + 启动同步 + /actuator/ppshard resync 保障。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO pp_payment.payment SELECT * FROM pp_payment.payment_0;
INSERT IGNORE INTO pp_payment.payment SELECT * FROM pp_payment.payment_1;
INSERT IGNORE INTO pp_payment.payment SELECT * FROM pp_payment.payment_2;
INSERT IGNORE INTO pp_payment.payment SELECT * FROM pp_payment.payment_3;

INSERT IGNORE INTO pp_payment.refund_record SELECT * FROM pp_payment.refund_record_0;
INSERT IGNORE INTO pp_payment.refund_record SELECT * FROM pp_payment.refund_record_1;
INSERT IGNORE INTO pp_payment.refund_record SELECT * FROM pp_payment.refund_record_2;
INSERT IGNORE INTO pp_payment.refund_record SELECT * FROM pp_payment.refund_record_3;

INSERT IGNORE INTO pp_accounting.account_balance SELECT * FROM pp_accounting.account_balance_0;
INSERT IGNORE INTO pp_accounting.account_balance SELECT * FROM pp_accounting.account_balance_1;
INSERT IGNORE INTO pp_accounting.account_balance SELECT * FROM pp_accounting.account_balance_2;
INSERT IGNORE INTO pp_accounting.account_balance SELECT * FROM pp_accounting.account_balance_3;

INSERT IGNORE INTO pp_accounting.ledger_entry SELECT * FROM pp_accounting.ledger_entry_0;
INSERT IGNORE INTO pp_accounting.ledger_entry SELECT * FROM pp_accounting.ledger_entry_1;
INSERT IGNORE INTO pp_accounting.ledger_entry SELECT * FROM pp_accounting.ledger_entry_2;
INSERT IGNORE INTO pp_accounting.ledger_entry SELECT * FROM pp_accounting.ledger_entry_3;

INSERT IGNORE INTO pp_trade.trade_order SELECT * FROM pp_trade.trade_order_0;
INSERT IGNORE INTO pp_trade.trade_order SELECT * FROM pp_trade.trade_order_1;
INSERT IGNORE INTO pp_trade.trade_order SELECT * FROM pp_trade.trade_order_2;
INSERT IGNORE INTO pp_trade.trade_order SELECT * FROM pp_trade.trade_order_3;

-- ---------------------------------------------------------------------------
-- 校验（只读）：原单表行数 = 各分片表行数之和（全程零删除）
-- ---------------------------------------------------------------------------
SELECT 'payment_legacy' AS item, COUNT(*) AS rows_total FROM pp_payment.payment
UNION ALL SELECT 'payment_shards', (SELECT COUNT(*) FROM pp_payment.payment_0)
    + (SELECT COUNT(*) FROM pp_payment.payment_1)
    + (SELECT COUNT(*) FROM pp_payment.payment_2)
    + (SELECT COUNT(*) FROM pp_payment.payment_3)
UNION ALL SELECT 'refund_legacy', COUNT(*) FROM pp_payment.refund_record
UNION ALL SELECT 'refund_shards', (SELECT COUNT(*) FROM pp_payment.refund_record_0)
    + (SELECT COUNT(*) FROM pp_payment.refund_record_1)
    + (SELECT COUNT(*) FROM pp_payment.refund_record_2)
    + (SELECT COUNT(*) FROM pp_payment.refund_record_3)
UNION ALL SELECT 'balance_legacy', COUNT(*) FROM pp_accounting.account_balance
UNION ALL SELECT 'balance_shards', (SELECT COUNT(*) FROM pp_accounting.account_balance_0)
    + (SELECT COUNT(*) FROM pp_accounting.account_balance_1)
    + (SELECT COUNT(*) FROM pp_accounting.account_balance_2)
    + (SELECT COUNT(*) FROM pp_accounting.account_balance_3)
UNION ALL SELECT 'entry_legacy', COUNT(*) FROM pp_accounting.ledger_entry
UNION ALL SELECT 'entry_shards', (SELECT COUNT(*) FROM pp_accounting.ledger_entry_0)
    + (SELECT COUNT(*) FROM pp_accounting.ledger_entry_1)
    + (SELECT COUNT(*) FROM pp_accounting.ledger_entry_2)
    + (SELECT COUNT(*) FROM pp_accounting.ledger_entry_3)
UNION ALL SELECT 'order_legacy', COUNT(*) FROM pp_trade.trade_order
UNION ALL SELECT 'order_shards', (SELECT COUNT(*) FROM pp_trade.trade_order_0)
    + (SELECT COUNT(*) FROM pp_trade.trade_order_1)
    + (SELECT COUNT(*) FROM pp_trade.trade_order_2)
    + (SELECT COUNT(*) FROM pp_trade.trade_order_3)
UNION ALL SELECT 'payment_request_index', COUNT(*) FROM pp_payment.payment_request_index
UNION ALL SELECT 'refund_request_index', COUNT(*) FROM pp_payment.refund_request_index;