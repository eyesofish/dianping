-- Add DB-level idempotency guard for voucher orders.
-- If duplicate data already exists, this ALTER TABLE will fail and duplicated rows should be cleaned first.
SET @idx_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_voucher_order'
      AND index_name = 'uk_user_voucher'
);

SET @ddl := IF(
    @idx_exists = 0,
    'ALTER TABLE `tb_voucher_order` ADD UNIQUE INDEX `uk_user_voucher`(`user_id`, `voucher_id`)',
    'SELECT ''uk_user_voucher already exists'''
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
