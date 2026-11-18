-- payment-pro 单实例多库：在共享 claim-shared-mysql 上按域建 9 库。
-- mysql 数据卷首次初始化后 docker-entrypoint-initdb.d 不再触发，需显式执行：
--   export PATH="$HOME/.docker/bin:$PATH"
--   docker exec -i claim-shared-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < deploy/payment-databases.sql
CREATE DATABASE IF NOT EXISTS pp_member     CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS pp_account    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS pp_accounting CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS pp_channel    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS pp_settle     CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS pp_notify     CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS pp_recon      CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS pp_trade      CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS pp_payment    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;