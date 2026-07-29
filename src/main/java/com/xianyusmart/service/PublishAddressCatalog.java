package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地发布行政区划目录
 */
class PublishAddressCatalog {

    private final List<Division> provinces;

    PublishAddressCatalog(ObjectMapper objectMapper) {
        try (InputStream input = PublishAddressCatalog.class.getResourceAsStream(
                "/data/china-division.json")) {
            if (input == null) {
                throw new IllegalStateException("缺少本地发布行政区划数据");
            }
            provinces = objectMapper.readValue(input, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("本地发布行政区划数据读取失败", e);
        }
    }

    Map<String, Object> resolve(Map<String, Object> request) {
        String provinceName = text(request.get("province"));
        String cityName = text(request.get("city"));
        String districtName = text(request.get("district"));
        Division province = find(provinces, provinceName);
        if (province == null) {
            return Map.of();
        }
        Division city = province.children().stream()
                .filter(item -> cityName.isBlank()
                        || item.name().equals(cityName)
                        || ("市辖区".equals(item.name()) && province.name().equals(cityName)))
                .findFirst()
                .orElse(null);
        if (city == null) {
            return Map.of();
        }
        Division district = districtName.isBlank()
                ? city.children().stream().findFirst().orElse(null)
                : find(city.children(), districtName);
        if (district == null) {
            return Map.of();
        }

        // 旧页面只传省市区文本时，补齐平台发布所需的区划编码和定位数据。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("province", province.name());
        result.put("city", "市辖区".equals(city.name()) ? province.name() : city.name());
        result.put("district", district.name());
        result.put("divisionId", district.code());
        result.put("gps", firstNonBlank(district.gps(), city.gps(), province.gps()));
        result.put("poiName", district.name());
        return result;
    }

    private Division find(List<Division> divisions, String name) {
        return divisions.stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record Division(String code, String name, String gps, List<Division> children) {
        private Division {
            children = children == null ? List.of() : children;
        }
    }
}
