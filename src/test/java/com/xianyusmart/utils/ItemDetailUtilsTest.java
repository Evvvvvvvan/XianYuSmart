package com.xianyusmart.utils;

import com.xianyusmart.entity.XianyuGoodsSku;
import com.xianyusmart.entity.XianyuGoodsSkuProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemDetailUtilsTest {

    private static final String IDLE_ITEM_SKU_RESPONSE = """
            {
              "ret": ["SUCCESS::调用成功"],
              "data": {
                "itemDO": {
                  "itemId": "123456",
                  "desc": "多规格商品",
                  "idleItemSkuList": [
                    {
                      "skuId": "5001",
                      "priceInCent": "1990",
                      "quantity": 3,
                      "propertyList": [
                        {"propertyId": 1, "propertyText": "颜色", "propertySortOrder": 1,
                         "valueId": 11, "valueText": "红", "valueSortOrder": 1},
                        {"propertyId": 2, "propertyText": "套餐", "propertySortOrder": 2,
                         "valueId": 21, "valueText": "标准", "valueSortOrder": 1}
                      ]
                    },
                    {
                      "skuId": "5002",
                      "priceInCent": 2990,
                      "quantity": 5,
                      "propertyList": [
                        {"propertyId": 1, "propertyText": "颜色", "propertySortOrder": 1,
                         "valueId": 12, "valueText": "蓝", "valueSortOrder": 2}
                      ]
                    }
                  ]
                }
              }
            }
            """;

    @Test
    void parsesIdleItemSkuListWithPropertyList() {
        List<XianyuGoodsSku> skus = ItemDetailUtils.extractSkuList(IDLE_ITEM_SKU_RESPONSE);

        assertEquals(2, skus.size());
        XianyuGoodsSku first = skus.get(0);
        assertEquals("5001", first.getSkuId());
        assertEquals(1990, first.getPrice());
        assertEquals(3, first.getQuantity());
        assertEquals("颜色:红 套餐:标准", first.getPropertyText());
        assertEquals("红 标准", first.getValueText());
        assertEquals(1, first.getPropertyId());
        assertEquals(11, first.getValueId());
        assertEquals("5002", skus.get(1).getSkuId());
        assertEquals(2990, skus.get(1).getPrice());
    }

    @Test
    void parsesSkuListWithVariantFieldNames() {
        String response = """
                {
                  "data": {
                    "itemDO": {
                      "skuList": [
                        {"id": "9001", "price": "1500", "stock": 2,
                         "properties": [{"propId": "3", "propertyName": "尺寸",
                                          "vid": "31", "valueName": "L"}]}
                      ]
                    }
                  }
                }
                """;

        List<XianyuGoodsSku> skus = ItemDetailUtils.extractSkuList(response);

        assertEquals(1, skus.size());
        XianyuGoodsSku sku = skus.get(0);
        assertEquals("9001", sku.getSkuId());
        assertEquals(1500, sku.getPrice());
        assertEquals(2, sku.getQuantity());
        assertEquals("尺寸:L", sku.getPropertyText());
        assertEquals(3, sku.getPropertyId());
        assertEquals(31, sku.getValueId());
    }

    @Test
    void deduplicatesRepeatedSkuIds() {
        String response = """
                {"data":{"itemDO":{"skuList":[
                  {"skuId":"1","price":100},
                  {"skuId":"1","price":200}
                ]}}}
                """;

        List<XianyuGoodsSku> skus = ItemDetailUtils.extractSkuList(response);

        assertEquals(1, skus.size());
        assertEquals(200, skus.get(0).getPrice());
    }

    @Test
    void skipsSkuWithoutRemoteId() {
        String response = """
                {"data":{"itemDO":{"skuList":[{"price":100,"quantity":1}]}}}
                """;

        assertTrue(ItemDetailUtils.extractSkuList(response).isEmpty());
    }

    @Test
    void findsNestedSkuArrayWhenTopLevelFieldMissing() {
        String response = """
                {"data":{"itemDO":{"itemId":"1","skuInfo":{"list":[{"skuId":"a1","priceInCent":100}]}}}}
                """;

        List<XianyuGoodsSku> skus = ItemDetailUtils.extractSkuList(response);

        assertEquals(1, skus.size());
        assertEquals("a1", skus.get(0).getSkuId());
    }

    @Test
    void extractsDistinctPropertyDimensions() {
        List<XianyuGoodsSkuProperty> properties =
                ItemDetailUtils.extractSkuPropertyList(IDLE_ITEM_SKU_RESPONSE);

        assertEquals(3, properties.size());
        assertEquals("颜色", properties.get(0).getPropertyText());
        assertEquals("红", properties.get(0).getValueText());
        assertEquals(1, properties.get(0).getPropertySortOrder());
    }
}
