package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuGoodsSkuProperty;

import java.util.List;

public interface GoodsSkuPropertyService {

    List<XianyuGoodsSkuProperty> listByXyGoodsId(String xyGoodsId);

    List<XianyuGoodsSkuProperty> listByXyGoodsId(String xyGoodsId, Long xianyuAccountId);

    void saveProperties(String xyGoodsId, Long xianyuAccountId, List<XianyuGoodsSkuProperty> propertyList);

    void deleteByXyGoodsId(String xyGoodsId, Long xianyuAccountId);
}
