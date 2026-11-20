-- ============================================================================
-- payment-pro I-5a ShardingSphere 试点：分片表初始化（单库分表方案）
--
-- 方案选型：单库分表，不新增 pp_settle_0..3 / pp_recon_0..3 库。理由：
--   ① 幂等表 idempotent_record 与业务写仍在同一库同一本地事务，五层幂等原子性不变；
--   ② ShedLock / dispute_order / sequences 等支撑表保持单库单表，调度互斥与差错平台零改动；
--   ③ 不新建库、不动连接串与权限，回滚只切 DataSource，风险面最小。
--
-- 数据保护红线（本文件严格遵守）：
--   * 只 CREATE TABLE IF NOT EXISTS 新建分片表；
--   * 既有行只 INSERT IGNORE 复制（additive），原表 settle_record / recon_report 原行全部保留；
--   * 绝不 DROP / TRUNCATE / DELETE / UPDATE 任何既有业务行；
--   * §5.1 步骤⑥「下线单库」不做 —— 单库永久保留以支持一键回滚。
--
-- 分片算法：CRC32(分片键) % 4（MySQL CRC32 与 Java java.util.zip.CRC32 同为 IEEE CRC-32，
--          故本文件的 SQL 复制与应用内 ShardingSphere 路由算法逐行一致）。
--   settle_record  按 merchant_id 分 4 片
--   recon_report   按 id 哈希分 4 片
--
-- 执行方式（幂等，可反复执行；应用启动时也会自动建表+双向复制，本文件是手工/审计通道）：
--   export PATH="$HOME/.docker/bin:$PATH"
--   set -a; . ~/.config/payment-pro/docker.env; set +a
--   docker exec -i -e PPDBPW="$MYSQL_PASSWORD" claim-shared-mysql \
--     sh -c 'mysql -uroot -p"$PPDBPW"' < deploy/payment-sharding-tables.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- pp_settle：settle_record_0..3（结构与原表逐列一致，含 uk_settle_biz 唯一键）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pp_settle.settle_record_0 LIKE pp_settle.settle_record;
CREATE TABLE IF NOT EXISTS pp_settle.settle_record_1 LIKE pp_settle.settle_record;
CREATE TABLE IF NOT EXISTS pp_settle.settle_record_2 LIKE pp_settle.settle_record;
CREATE TABLE IF NOT EXISTS pp_settle.settle_record_3 LIKE pp_settle.settle_record;

-- 既有行 additive 复制到对应分片（原表原行保留；主键/唯一键冲突自动跳过）
INSERT IGNORE INTO pp_settle.settle_record_0 SELECT * FROM pp_settle.settle_record WHERE CRC32(merchant_id) % 4 = 0;
INSERT IGNORE INTO pp_settle.settle_record_1 SELECT * FROM pp_settle.settle_record WHERE CRC32(merchant_id) % 4 = 1;
INSERT IGNORE INTO pp_settle.settle_record_2 SELECT * FROM pp_settle.settle_record WHERE CRC32(merchant_id) % 4 = 2;
INSERT IGNORE INTO pp_settle.settle_record_3 SELECT * FROM pp_settle.settle_record WHERE CRC32(merchant_id) % 4 = 3;

-- ---------------------------------------------------------------------------
-- pp_recon：recon_report_0..3（按 id 哈希）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pp_recon.recon_report_0 LIKE pp_recon.recon_report;
CREATE TABLE IF NOT EXISTS pp_recon.recon_report_1 LIKE pp_recon.recon_report;
CREATE TABLE IF NOT EXISTS pp_recon.recon_report_2 LIKE pp_recon.recon_report;
CREATE TABLE IF NOT EXISTS pp_recon.recon_report_3 LIKE pp_recon.recon_report;

INSERT IGNORE INTO pp_recon.recon_report_0 SELECT * FROM pp_recon.recon_report WHERE CRC32(id) % 4 = 0;
INSERT IGNORE INTO pp_recon.recon_report_1 SELECT * FROM pp_recon.recon_report WHERE CRC32(id) % 4 = 1;
INSERT IGNORE INTO pp_recon.recon_report_2 SELECT * FROM pp_recon.recon_report WHERE CRC32(id) % 4 = 2;
INSERT IGNORE INTO pp_recon.recon_report_3 SELECT * FROM pp_recon.recon_report WHERE CRC32(id) % 4 = 3;

-- ---------------------------------------------------------------------------
-- 回滚一致性补偿：分片期新增行 additive 补回原单表
-- （应用内 ShardMirror 已在事务 afterCommit 实时镜像；本段是重启/手工补偿通道）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO pp_settle.settle_record SELECT * FROM pp_settle.settle_record_0;
INSERT IGNORE INTO pp_settle.settle_record SELECT * FROM pp_settle.settle_record_1;
INSERT IGNORE INTO pp_settle.settle_record SELECT * FROM pp_settle.settle_record_2;
INSERT IGNORE INTO pp_settle.settle_record SELECT * FROM pp_settle.settle_record_3;

INSERT IGNORE INTO pp_recon.recon_report SELECT * FROM pp_recon.recon_report_0;
INSERT IGNORE INTO pp_recon.recon_report SELECT * FROM pp_recon.recon_report_1;
INSERT IGNORE INTO pp_recon.recon_report SELECT * FROM pp_recon.recon_report_2;
INSERT IGNORE INTO pp_recon.recon_report SELECT * FROM pp_recon.recon_report_3;

-- ---------------------------------------------------------------------------
-- 校验（只读）：原单表行数 = 各分片表行数之和
-- ---------------------------------------------------------------------------
SELECT 'settle_legacy' AS item, COUNT(*) AS rows_total FROM pp_settle.settle_record
UNION ALL SELECT 'settle_shards', (SELECT COUNT(*) FROM pp_settle.settle_record_0)
    + (SELECT COUNT(*) FROM pp_settle.settle_record_1)
    + (SELECT COUNT(*) FROM pp_settle.settle_record_2)
    + (SELECT COUNT(*) FROM pp_settle.settle_record_3)
UNION ALL SELECT 'recon_legacy', COUNT(*) FROM pp_recon.recon_report
UNION ALL SELECT 'recon_shards', (SELECT COUNT(*) FROM pp_recon.recon_report_0)
    + (SELECT COUNT(*) FROM pp_recon.recon_report_1)
    + (SELECT COUNT(*) FROM pp_recon.recon_report_2)
    + (SELECT COUNT(*) FROM pp_recon.recon_report_3);