package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.entity.XianyuGoodsSku;
import com.xianyusmart.entity.XianyuGoodsSkuProperty;
import com.xianyusmart.service.GoodsSkuService;
import com.xianyusmart.service.GoodsSkuPropertyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/goods-sku")
public class GoodsSkuController {

    @Autowired
    private GoodsSkuService goodsSkuService;

    @Autowired
    private GoodsSkuPropertyService goodsSkuPropertyService;

    @PostMapping("/list")
    public ResultObject<List<XianyuGoodsSku>> listByGoodsId(@RequestParam("xyGoodsId") String xyGoodsId,
                                                            @RequestParam("xianyuAccountId") Long xianyuAccountId) {
        try {
            List<XianyuGoodsSku> skuList = goodsSkuService.listByXyGoodsId(xyGoodsId, xianyuAccountId);
            return ResultObject.success(skuList);
        } catch (Exception e) {
            log.error("查询商品SKU列表失败: xianyuAccountId={}, xyGoodsId={}", xianyuAccountId, xyGoodsId, e);
            return ResultObject.failed("查询商品SKU列表失败: " + e.getMessage());
        }
    }

    @PostMapping("/detail")
    public ResultObject<Map<String, Object>> skuDetail(@RequestParam("xyGoodsId") String xyGoodsId,
                                                        @RequestParam("xianyuAccountId") Long xianyuAccountId) {
        try {
            List<XianyuGoodsSku> skuList = goodsSkuService.listByXyGoodsId(xyGoodsId, xianyuAccountId);
            List<XianyuGoodsSkuProperty> propertyList = goodsSkuPropertyService
                    .listByXyGoodsId(xyGoodsId, xianyuAccountId);
            Map<String, Object> data = new HashMap<>();
            data.put("skuList", skuList);
            data.put("propertyList", propertyList);
            return ResultObject.success(data);
        } catch (Exception e) {
            log.error("查询商品SKU详情失败: xianyuAccountId={}, xyGoodsId={}", xianyuAccountId, xyGoodsId, e);
            return ResultObject.failed("查询商品SKU详情失败: " + e.getMessage());
        }
    }
}
