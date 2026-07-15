CREATE INDEX idx_goods_order_account_delivery_created
    ON xianyu_goods_order (xianyu_account_id, delivery_status, create_time);
