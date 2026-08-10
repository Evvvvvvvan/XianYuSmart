package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuGoodsSku;

import java.util.List;

public interface GoodsSkuService {

    List<XianyuGoodsSku> listByXyGoodsId(String xyGoodsId);

    List<XianyuGoodsSku> listByXyGoodsId(String xyGoodsId, Long xianyuAccountId);

    int countByXyGoodsId(String xyGoodsId);

    int countByXyGoodsId(String xyGoodsId, Long xianyuAccountId);

    XianyuGoodsSku findByXyGoodsIdAndSkuId(String xyGoodsId, Long xianyuAccountId, String skuId);

    void saveSkus(String xyGoodsId, Long xianyuAccountId, List<XianyuGoodsSku> skuList);

    void deleteByXyGoodsId(String xyGoodsId, Long xianyuAccountId);
}
