-- 闲鱼商品标题（尤其是详情接口返回的完整标题）可能超过 200 字符，
-- 商机导入货源时触发 MysqlDataTruncation: Data too long for column 'name'。
-- 加宽 merchant_resource.name，代码侧同步做截断保护。
ALTER TABLE merchant_resource MODIFY COLUMN name VARCHAR(512) NOT NULL;
