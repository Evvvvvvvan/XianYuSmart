package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.xianyusmart.config.PlaywrightManager;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.entity.MerchantResource;
import com.xianyusmart.utils.XianyuApiCallUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商品搜索、发布和状态管理执行器
 */
@Slf4j
@Service
public class PlatformPublishService {

    private static final Pattern GOODS_ID_PATTERN = Pattern.compile("(?:id=|/item/)(\\d{8,})");
    private final PlaywrightManager playwrightManager;
    private final AccountService accountService;
    private final ObjectMapper objectMapper;
    private final XianyuApiCallUtils apiCallUtils;
    private final ImageUploadService imageUploadService;
    private final PlatformMarketplaceParser responseParser;

    public PlatformPublishService(PlaywrightManager playwrightManager,
                                  AccountService accountService,
                                  ObjectMapper objectMapper,
                                  XianyuApiCallUtils apiCallUtils,
                                  ImageUploadService imageUploadService) {
        this.playwrightManager = playwrightManager;
        this.accountService = accountService;
        this.objectMapper = objectMapper;
        this.apiCallUtils = apiCallUtils;
        this.imageUploadService = imageUploadService;
        this.responseParser = new PlatformMarketplaceParser(objectMapper);
    }

    public Map<String, Object> publish(MerchantResource material, Long accountId) {
        return publish(material, accountId, Map.of());
    }

    public Map<String, Object> publish(MerchantResource material, Long accountId, Map<String, Object> address) {
        String cookieText = accountService.getCookieByAccountId(accountId);
        if (cookieText == null || cookieText.isBlank()) {
            throw new IllegalStateException("账号Cookie不可用");
        }
        Map<String, Object> data = readData(material.getDataJson());
        String title = text(data.get("title"));
        if (title.isBlank()) {
            title = material.getName();
        }
        String description = text(data.get("description"));
        if (description.isBlank()) {
            description = title;
        }
        List<String> sourceImages = extractImages(data.get("images")).stream().limit(9).toList();
        validatePublishInput(title, description, sourceImages, material.getAmount(), material.getStock());

        Map<String, Object> category = recommendCategory(accountId, cookieText, title, description, sourceImages);
        Map<String, Object> platformAddress = resolveAddress(accountId, cookieText, address, data);
        List<String> cdnImages = new ArrayList<>();
        for (String image : sourceImages) {
            String normalizedImage = normalizeImageUrl(image);
            if (isPlatformImage(normalizedImage)) {
                cdnImages.add(normalizedImage);
                continue;
            }
            ResultObject<String> upload = imageUploadService.uploadImageFromUrl(accountId, normalizedImage);
            if (upload.getCode() != 200 || upload.getData() == null || upload.getData().isBlank()) {
                throw new IllegalStateException("商品图片上传失败: " + upload.getMsg());
            }
            cdnImages.add(upload.getData());
        }

        Map<String, Object> publishData = buildPublishData(
                title, description, material.getAmount(), material.getStock(), cdnImages, category, platformAddress);
        XianyuApiCallUtils.ApiCallResult publishResult = apiCallUtils.callApiWithRetry(
                accountId, "mtop.idle.pc.idleitem.publish", publishData, cookieText);
        if (!publishResult.isSuccess()) {
            throw new IllegalStateException("平台拒绝发布: " + publishResult.getErrorMessage());
        }
        String itemId = responseParser.extractPublishedItemId(publishResult.getResponse());
        if (itemId.isBlank()) {
            throw new IllegalStateException("平台返回成功但缺少商品ID，发布结果无法确认");
        }
        return Map.of(
                "success", true,
                "itemId", itemId,
                "url", "https://www.goofish.com/item?id=" + itemId,
                "category", category,
                "imageCount", cdnImages.size()
        );
    }

    public Map<String, Object> preflight(Map<String, Object> request, Long accountId) {
        String cookieText = accountService.getCookieByAccountId(accountId);
        if (cookieText == null || cookieText.isBlank()) {
            throw new IllegalStateException("账号Cookie不可用");
        }
        String title = text(request.get("name"));
        String description = text(request.get("description"));
        List<String> images = extractImages(request.get("images")).stream().limit(9).toList();
        java.math.BigDecimal amount;
        try {
            amount = new java.math.BigDecimal(text(request.get("amount")));
        } catch (Exception e) {
            throw new IllegalArgumentException("商品价格格式无效");
        }
        int stock;
        try {
            stock = Integer.parseInt(text(request.get("stock")));
        } catch (Exception e) {
            stock = 1;
        }
        validatePublishInput(title, description, images, amount, stock);
        Map<String, Object> category = recommendCategory(accountId, cookieText, title, description, images);
        Map<String, Object> address = resolveAddress(accountId, cookieText, request, request);
        return Map.of(
                "valid", true,
                "category", category,
                "address", address,
                "imageCount", images.size()
        );
    }

    public Map<String, Object> delete(Long accountId, String goodsId) {
        String cookieText = accountService.getCookieByAccountId(accountId);
        if (cookieText == null || cookieText.isBlank()) {
            throw new IllegalStateException("账号Cookie不可用");
        }
        try (BrowserContext context = playwrightManager.createContext()) {
            addCookies(context, cookieText);
            Page page = context.newPage();
            page.navigate("https://www.goofish.com/item?id=" + goodsId,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
            page.waitForTimeout(2000);
            ensureLoggedIn(page);
            Locator deleteButton = page.locator("button:has-text(\"删除\"),button:has-text(\"下架\")").first();
            if (deleteButton.count() == 0) {
                throw new IllegalStateException("商品页面未找到删除或下架操作");
            }
            deleteButton.click();
            Locator confirmButton = page.locator("button:has-text(\"确定\"),button:has-text(\"确认\")").last();
            if (confirmButton.count() > 0) {
                confirmButton.click();
            }
            page.waitForTimeout(2000);
            return Map.of("success", true, "itemId", goodsId);
        }
    }

    public Map<String, Object> changeListingStatus(Long accountId, String goodsId, boolean onSale) {
        String cookieText = accountService.getCookieByAccountId(accountId);
        if (cookieText == null || cookieText.isBlank()) {
            throw new IllegalStateException("账号 Cookie 不可用");
        }
        try (BrowserContext context = playwrightManager.createContext()) {
            addCookies(context, cookieText);
            Page page = context.newPage();
            page.navigate("https://www.goofish.com/item?id=" + goodsId,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
            page.waitForTimeout(2000);
            ensureLoggedIn(page);
            String selector = onSale
                    ? "button:has-text(\"上架\"),button:has-text(\"重新上架\")"
                    : "button:has-text(\"下架\")";
            Locator actionButton = page.locator(selector).first();
            if (actionButton.count() == 0) {
                throw new IllegalStateException(onSale ? "商品页面未找到上架操作" : "商品页面未找到下架操作");
            }
            actionButton.click();
            Locator confirmButton = page.locator("button:has-text(\"确定\"),button:has-text(\"确认\")").last();
            if (confirmButton.count() > 0) {
                confirmButton.click();
            }
            page.waitForTimeout(2000);
            return Map.of("success", true, "itemId", goodsId, "onSale", onSale);
        }
    }

    public Map<String, Object> collect(String sourceUrl, Long accountId) {
        validatePlatformUrl(sourceUrl);
        try (BrowserContext context = playwrightManager.createContext()) {
            if (accountId != null) {
                String cookieText = accountService.getCookieByAccountId(accountId);
                if (cookieText != null && !cookieText.isBlank()) {
                    addCookies(context, cookieText);
                }
            }
            Page page = context.newPage();
            page.navigate(sourceUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
            page.waitForTimeout(2000);
            String bodyText = page.locator("body").innerText();
            if (bodyText.contains("异常流量") || bodyText.contains("滑块验证")) {
                throw new IllegalStateException("商品采集触发平台验证，请稍后重试");
            }
            Map<String, Object> result = new HashMap<>();
            String title = firstText(page, "h1,[class*='title']");
            if (title.isBlank()) {
                title = attribute(page, "meta[property='og:title']", "content");
            }
            String description = attribute(page, "meta[name='description']", "content");
            LinkedHashSet<String> images = new LinkedHashSet<>();
            String mainImage = attribute(page, "meta[property='og:image']", "content");
            if (!mainImage.isBlank()) {
                images.add(mainImage);
            }
            for (Locator image : page.locator("img").all()) {
                String src = image.getAttribute("src");
                if (src != null && src.startsWith("https://")) {
                    images.add(src);
                }
                if (images.size() >= 9) {
                    break;
                }
            }
            result.put("title", title.isBlank() ? "采集商品" : title);
            result.put("description", description);
            result.put("images", new ArrayList<>(images));
            result.put("sourceUrl", page.url());
            Matcher matcher = GOODS_ID_PATTERN.matcher(page.url());
            if (matcher.find()) {
                result.put("itemId", matcher.group(1));
            }
            return result;
        }
    }

    public List<Map<String, Object>> search(String keyword, Long accountId, int limit) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("请输入商品关键词");
        }
        if (accountId == null) {
            throw new IllegalArgumentException("请选择用于搜索的账号");
        }
        String cookieText = accountService.getCookieByAccountId(accountId);
        if (cookieText == null || cookieText.isBlank()) {
            throw new IllegalStateException("账号Cookie不可用");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pageNumber", 1);
        data.put("keyword", keyword.trim());
        data.put("fromFilter", false);
        data.put("rowsPerPage", Math.max(1, Math.min(limit, 50)));
        data.put("sortValue", "");
        data.put("sortField", "");
        data.put("customDistance", "");
        data.put("gps", "");
        data.put("propValueStr", Map.of("searchFilter", ""));
        data.put("customGps", "");
        data.put("searchReqFromPage", "pcSearch");
        XianyuApiCallUtils.ApiCallResult result = apiCallUtils.callApiWithRetry(
                accountId, "mtop.taobao.idlemtopsearch.pc.search", data, cookieText);
        if (!result.isSuccess()) {
            throw new IllegalStateException("平台搜索失败: " + result.getErrorMessage());
        }
        return responseParser.parseSearchResponse(result.getResponse(), Math.max(1, Math.min(limit, 50)));
    }

    private Map<String, Object> recommendCategory(Long accountId, String cookieText, String title,
                                                   String description, List<String> images) {
        List<Map<String, Object>> imageInfos = new ArrayList<>();
        for (int index = 0; index < Math.min(images.size(), 3); index++) {
            imageInfos.add(Map.of(
                    "url", normalizeImageUrl(images.get(index)),
                    "heightSize", 0,
                    "widthSize", 0,
                    "major", index == 0,
                    "type", 0,
                    "status", "done",
                    "isQrCode", false,
                    "extraInfo", Map.of("isH", "false", "isT", "false", "raw", "false")
            ));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", title);
        data.put("description", description);
        data.put("imageInfos", imageInfos);
        data.put("lockCpv", false);
        data.put("multiSKU", false);
        data.put("publishScene", "mainPublish");
        data.put("scene", "newPublishChoice");
        data.put("uniqueCode", String.valueOf(System.currentTimeMillis()));
        XianyuApiCallUtils.ApiCallResult result = apiCallUtils.callApiWithRetry(
                accountId,
                "mtop.taobao.idle.kgraph.property.recommend",
                "2.0",
                data,
                cookieText,
                null,
                Map.of("type", "originaljson", "spm_cnt", "a21ybx.publish.0.0")
        );
        if (!result.isSuccess()) {
            throw new IllegalStateException("平台类目识别失败: " + result.getErrorMessage());
        }
        Map<String, Object> response = readData(result.getResponse());
        Map<String, Object> responseData = map(response.get("data"));
        Object prediction = responseData.get("categoryPredictResult");
        Map<String, Object> category = prediction instanceof List<?> list && !list.isEmpty()
                ? map(list.get(0)) : map(prediction);
        if (text(category.get("catId")).isBlank()) {
            throw new IllegalStateException("平台未返回可发布类目，请调整标题和详情后重试");
        }
        return Map.of(
                "catId", text(category.get("catId")),
                "catName", text(category.get("catName")),
                "channelCatId", text(category.get("channelCatId")),
                "tbCatId", text(category.get("tbCatId"))
        );
    }

    private Map<String, Object> resolveAddress(Long accountId, String cookieText,
                                               Map<String, Object> address, Map<String, Object> data) {
        Map<String, Object> requested = new LinkedHashMap<>();
        requested.putAll(data);
        requested.putAll(address);
        if (!text(requested.get("divisionId")).isBlank()) {
            return normalizeAddress(requested);
        }
        XianyuApiCallUtils.ApiCallResult result = apiCallUtils.callApiWithRetry(
                accountId, "mtop.idle.pc.idleitem.preget", Map.of(), cookieText);
        if (!result.isSuccess()) {
            throw new IllegalStateException("平台默认发布地址读取失败: " + result.getErrorMessage());
        }
        Map<String, Object> platformAddress = responseParser.extractDefaultAddress(result.getResponse());
        if (platformAddress.isEmpty()) {
            throw new IllegalStateException("平台账号未返回默认发布地址，请先在闲鱼发布页保存常用位置");
        }
        return normalizeAddress(platformAddress);
    }

    private Map<String, Object> normalizeAddress(Map<String, Object> address) {
        Map<String, Object> result = new LinkedHashMap<>();
        String gps = firstValue(address, "gps");
        if (gps.isBlank()) {
            String longitude = firstValue(address, "longitude");
            String latitude = firstValue(address, "latitude");
            if (!longitude.isBlank() && !latitude.isBlank()) {
                gps = longitude + "," + latitude;
            }
        }
        result.put("prov", firstValue(address, "prov", "province"));
        result.put("city", firstValue(address, "city"));
        result.put("area", firstValue(address, "area", "district"));
        result.put("divisionId", numberValue(address.get("divisionId")));
        result.put("gps", gps);
        result.put("poiId", firstValue(address, "poiId"));
        result.put("poiName", firstValue(address, "poiName", "poi", "detail"));
        return result;
    }

    private Map<String, Object> buildPublishData(String title, String description,
                                                 java.math.BigDecimal amount, Integer stock,
                                                 List<String> images, Map<String, Object> category,
                                                 Map<String, Object> address) {
        List<Map<String, Object>> imageList = new ArrayList<>();
        for (int index = 0; index < images.size(); index++) {
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("url", images.get(index));
            image.put("heightSize", 0);
            image.put("widthSize", 0);
            image.put("major", index == 0);
            image.put("type", 0);
            image.put("status", "done");
            image.put("isQrCode", false);
            image.put("extraInfo", Map.of("isH", "false", "isT", "false", "raw", "false"));
            imageList.add(image);
        }
        int quantity = Math.max(1, Math.min(stock == null ? 1 : stock, 9999));
        String priceInCent = amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
        Map<String, Object> publishData = new LinkedHashMap<>();
        publishData.put("freebies", false);
        publishData.put("itemTypeStr", "b");
        publishData.put("quantity", String.valueOf(quantity));
        publishData.put("simpleItem", "true");
        publishData.put("defaultPrice", false);
        publishData.put("uniqueCode", String.valueOf(System.currentTimeMillis()));
        publishData.put("sourceId", "pcMainPublish");
        publishData.put("bizcode", "pcMainPublish");
        publishData.put("publishScene", "pcMainPublish");
        publishData.put("imageInfoDOList", imageList);
        publishData.put("itemLabelExtList", List.of());
        publishData.put("itemTextDTO", Map.of(
                "desc", description,
                "title", title,
                "titleDescSeparate", false
        ));
        publishData.put("itemCatDTO", category);
        publishData.put("itemPriceDTO", Map.of("priceInCent", priceInCent));
        publishData.put("itemPostFeeDTO", Map.of(
                "canFreeShipping", false,
                "supportFreight", false,
                "onlyTakeSelf", false,
                "templateId", "0"
        ));
        publishData.put("itemAddrDTO", address);
        publishData.put("userRightsProtocols", List.of(
                Map.of("enable", false, "serviceCode", "FAST_DELIVERY_48_HOUR"),
                Map.of("enable", false, "serviceCode", "FAST_DELIVERY_24_HOUR"),
                Map.of("enable", false, "serviceCode", "VIRTUAL_NONCONFORMITY_FREE_REFUND_SERVICE"),
                Map.of("enable", false, "serviceCode", "SKILL_PLAY_NO_MIND")
        ));
        publishData.put("itemSkuList", List.of(Map.of(
                "priceInCent", priceInCent,
                "quantity", String.valueOf(quantity),
                "propertyList", List.of()
        )));
        return publishData;
    }

    private void validatePublishInput(String title, String description, List<String> images,
                                      java.math.BigDecimal amount, Integer stock) {
        if (title == null || title.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("商品标题和详情不能为空");
        }
        if (title.length() > 120 || description.length() > 3000) {
            throw new IllegalArgumentException("商品标题或详情超过平台长度限制");
        }
        if (images.isEmpty() || images.size() > 9) {
            throw new IllegalArgumentException("商品图片数量必须为1至9张");
        }
        for (String image : images) {
            URI uri = URI.create(normalizeImageUrl(image));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("商品图片必须使用有效的HTTPS地址");
            }
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("商品价格必须大于0");
        }
        if (stock != null && stock < 1) {
            throw new IllegalArgumentException("商品库存必须大于0");
        }
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, child) -> result.put(String.valueOf(key), child));
        return result;
    }

    private String firstValue(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            String value = text(source.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Object numberValue(Object value) {
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String normalizeImageUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        return value.startsWith("http://") ? "https://" + value.substring(7) : value;
    }

    private boolean isPlatformImage(String value) {
        try {
            String host = URI.create(value).getHost();
            return host != null && (host.equals("alicdn.com") || host.endsWith(".alicdn.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureLoggedIn(Page page) {
        String url = page.url();
        String bodyText = page.locator("body").innerText();
        if (url.contains("login") || bodyText.contains("请先登录") || bodyText.contains("扫码登录")) {
            throw new IllegalStateException("账号Cookie已失效");
        }
    }

    private void validatePlatformUrl(String sourceUrl) {
        URI uri = URI.create(sourceUrl);
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equals("goofish.com") || host.endsWith(".goofish.com"))) {
            throw new IllegalArgumentException("仅支持HTTPS闲鱼商品地址");
        }
    }

    private String firstText(Page page, String selector) {
        Locator locator = page.locator(selector).first();
        return locator.count() == 0 ? "" : locator.innerText().trim();
    }

    private String attribute(Page page, String selector, String name) {
        Locator locator = page.locator(selector).first();
        if (locator.count() == 0) {
            return "";
        }
        String value = locator.getAttribute(name);
        return value == null ? "" : value.trim();
    }

    private void addCookies(BrowserContext context, String cookieText) {
        List<Cookie> cookies = new ArrayList<>();
        for (String part : cookieText.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && !pair[0].isBlank()) {
                cookies.add(new Cookie(pair[0].trim(), pair[1].trim())
                        .setDomain(".goofish.com").setPath("/").setSecure(true));
            }
        }
        context.addCookies(cookies);
    }

    private List<String> extractImages(Object images) {
        if (images instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(value -> !value.isBlank()).toList();
        }
        if (images instanceof String value && !value.isBlank()) {
            return List.of(value.split(",")).stream().map(String::trim).filter(item -> !item.isBlank()).toList();
        }
        return List.of();
    }

    private Map<String, Object> readData(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(dataJson, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("素材数据格式错误", e);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
