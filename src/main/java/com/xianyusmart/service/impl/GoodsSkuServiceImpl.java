package com.xianyusmart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.entity.XianyuGoodsSku;
import com.xianyusmart.mapper.XianyuGoodsSkuMapper;
import com.xianyusmart.service.GoodsSkuService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class GoodsSkuServiceImpl implements GoodsSkuService {

    @Autowired
    private XianyuGoodsSkuMapper goodsSkuMapper;

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String getCurrentTimeString() {
        return LocalDateTime.now().format(DATETIME_FORMATTER);
    }

    @Override
    public List<XianyuGoodsSku> listByXyGoodsId(String xyGoodsId) {
        LambdaQueryWrapper<XianyuGoodsSku> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuGoodsSku::getXyGoodsId, xyGoodsId);
        wrapper.orderByAsc(XianyuGoodsSku::getPropertySortOrder, XianyuGoodsSku::getValueSortOrder);
        return goodsSkuMapper.selectList(wrapper);
    }

    @Override
    public List<XianyuGoodsSku> listByXyGoodsId(String xyGoodsId, Long xianyuAccountId) {
        LambdaQueryWrapper<XianyuGoodsSku> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuGoodsSku::getXianyuAccountId, xianyuAccountId)
                .eq(XianyuGoodsSku::getXyGoodsId, xyGoodsId);
        wrapper.orderByAsc(XianyuGoodsSku::getPropertySortOrder, XianyuGoodsSku::getValueSortOrder);
        return goodsSkuMapper.selectList(wrapper);
    }

    @Override
    public int countByXyGoodsId(String xyGoodsId) {
        LambdaQueryWrapper<XianyuGoodsSku> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuGoodsSku::getXyGoodsId, xyGoodsId);
        return Math.toIntExact(goodsSkuMapper.selectCount(wrapper));
    }

    @Override
    public int countByXyGoodsId(String xyGoodsId, Long xianyuAccountId) {
        LambdaQueryWrapper<XianyuGoodsSku> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuGoodsSku::getXianyuAccountId, xianyuAccountId)
                .eq(XianyuGoodsSku::getXyGoodsId, xyGoodsId);
        return Math.toIntExact(goodsSkuMapper.selectCount(wrapper));
    }

    @Override
    public XianyuGoodsSku findByXyGoodsIdAndSkuId(String xyGoodsId, Long xianyuAccountId, String skuId) {
        LambdaQueryWrapper<XianyuGoodsSku> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuGoodsSku::getXianyuAccountId, xianyuAccountId)
                .eq(XianyuGoodsSku::getXyGoodsId, xyGoodsId)
                .eq(XianyuGoodsSku::getSkuId, skuId);
        return goodsSkuMapper.selectOne(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSkus(String xyGoodsId, Long xianyuAccountId, List<XianyuGoodsSku> skuList) {
        deleteByXyGoodsId(xyGoodsId, xianyuAccountId);

        String now = getCurrentTimeString();
        for (XianyuGoodsSku sku : skuList) {
            sku.setXyGoodsId(xyGoodsId);
            sku.setXianyuAccountId(xianyuAccountId);
            sku.setCreatedTime(now);
            sku.setUpdatedTime(now);
            goodsSkuMapper.insert(sku);
        }
        log.info("保存商品SKU: xyGoodsId={}, count={}", xyGoodsId, skuList.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByXyGoodsId(String xyGoodsId, Long xianyuAccountId) {
        LambdaQueryWrapper<XianyuGoodsSku> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuGoodsSku::getXianyuAccountId, xianyuAccountId)
                .eq(XianyuGoodsSku::getXyGoodsId, xyGoodsId);
        goodsSkuMapper.delete(wrapper);
    }
}
