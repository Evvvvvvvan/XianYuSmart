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

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
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
    private static final Pattern SHOP_USER_ID_PATTERN =
            Pattern.compile("(?i)(?:[?&](?:userId|sellerId|user_id)=)(\\d{5,})");
    private final PlaywrightManager playwrightManager;
    private final AccountService accountService;
    private final ObjectMapper objectMapper;
    private final XianyuApiCallUtils apiCallUtils;
    private final ImageUploadService imageUploadService;
    private final GoodsInfoService goodsInfoService;
    private final PlatformMarketplaceParser responseParser;
    private final PublishAddressCatalog addressCatalog;

    public PlatformPublishService(PlaywrightManager playwrightManager,
                                  AccountService accountService,
                                  ObjectMapper objectMapper,
                                  XianyuApiCallUtils apiCallUtils,
                                  ImageUploadService imageUploadService,
                                  GoodsInfoService goodsInfoService) {
        this.playwrightManager = playwrightManager;
        this.accountService = accountService;
        this.objectMapper = objectMapper;
        this.apiCallUtils = apiCallUtils;
        this.imageUploadService = imageUploadService;
        this.goodsInfoService = goodsInfoService;
        this.responseParser = new PlatformMarketplaceParser(objectMapper);
        this.addressCatalog = new PublishAddressCatalog(objectMapper);
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
        boolean localSynced = true;
        try {
            persistPublishedItem(itemId, title, description, material.getAmount(), cdnImages, accountId);
        } catch (Exception e) {
            localSynced = false;
            log.error("平台商品发布成功但本地商品记录保存失败: itemId={}, accountId={}", itemId, accountId, e);
        }
        return Map.of(
                "success", true,
                "itemId", itemId,
                "url", "https://www.goofish.com/item?id=" + itemId,
                "category", category,
                "imageCount", cdnImages.size(),
                "localSynced", localSynced
        );
    }

    private void persistPublishedItem(String itemId, String title, String description, BigDecimal amount,
                                      List<String> images, Long accountId) {
        String infoPic;
        try {
            infoPic = objectMapper.writeValueAsString(
                    images.stream().map(image -> Map.of("url", image)).toList());
        } catch (Exception e) {
            throw new IllegalStateException("商品图片信息序列化失败", e);
        }

        // 发布成功后立即落本地商品主记录，保证自动发货配置可直接关联。
        if (!goodsInfoService.savePublishedGoods(
                itemId,
                accountId,
                title,
                images.isEmpty() ? null : images.get(0),
                infoPic,
                description,
                "https://www.goofish.com/item?id=" + itemId,
                amount.stripTrailingZeros().toPlainString())) {
            throw new IllegalStateException("本地商品记录保存失败");
        }
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
        XianyuApiCallUtils.ApiCallResult offShelfResult = apiCallUtils.callApiWithRetry(
                accountId, "mtop.taobao.idle.item.downshelf", "2.0",
                Map.of("itemId", goodsId), cookieText, null, null);
        if (!offShelfResult.isSuccess()) {
            throw new IllegalStateException("平台下架失败: " + offShelfResult.getErrorMessage());
        }
        String refreshedCookie = accountService.getCookieByAccountId(accountId);
        XianyuApiCallUtils.ApiCallResult deleteResult = apiCallUtils.callApiWithRetry(
                accountId, "com.taobao.idle.item.delete", "1.1",
                Map.of("itemId", goodsId),
                refreshedCookie == null || refreshedCookie.isBlank() ? cookieText : refreshedCookie,
                null, null);
        if (!deleteResult.isSuccess()) {
            throw new IllegalStateException("商品已下架，但平台删除失败: " + deleteResult.getErrorMessage());
        }
        return Map.of("success", true, "itemId", goodsId, "deleted", true);
    }

    public Map<String, Object> changeListingStatus(Long accountId, String goodsId, boolean onSale) {
        String cookieText = accountService.getCookieByAccountId(accountId);
        if (cookieText == null || cookieText.isBlank()) {
            throw new IllegalStateException("账号 Cookie 不可用");
        }
        if (!onSale) {
            XianyuApiCallUtils.ApiCallResult result = apiCallUtils.callApiWithRetry(
                    accountId, "mtop.taobao.idle.item.downshelf", "2.0",
                    Map.of("itemId", goodsId), cookieText, null, null);
            if (!result.isSuccess()) {
                throw new IllegalStateException("平台下架失败: " + result.getErrorMessage());
            }
            return Map.of("success", true, "itemId", goodsId, "onSale", false);
        }
        try (BrowserContext context = playwrightManager.createContext()) {
            addCookies(context, cookieText);
            Page page = context.newPage();
            page.navigate("https://www.goofish.com/item?id=" + goodsId,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
            page.waitForTimeout(2000);
            ensureLoggedIn(page);
            String selector = "button:has-text(\"上架\"),button:has-text(\"重新上架\")";
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
        if (accountId == null) {
            throw new IllegalArgumentException("请选择用于采集的账号");
        }
        Matcher matcher = GOODS_ID_PATTERN.matcher(sourceUrl);
        if (!matcher.find()) {
            throw new IllegalArgumentException("闲鱼商品链接缺少商品ID");
        }
        String itemId = matcher.group(1);
        String cookieText = accountService.getCookieByAccountId(accountId);
        if (cookieText == null || cookieText.isBlank()) {
            throw new IllegalStateException("账号Cookie不可用");
        }
        // 商品采集复用 XianYuApis 的签名商品详情接口，避免页面结构变化导致采集失效。
        XianyuApiCallUtils.ApiCallResult result = apiCallUtils.callApiWithRetry(
                accountId,
                "mtop.taobao.idle.pc.detail",
                Map.of("itemId", itemId),
                cookieText,
                null,
                Map.of(
                        "spm_cnt", "a21ybx.im.0.0",
                        "spm_pre", "a21ybx.item.want.1"
                ));
        if (!result.isSuccess()) {
            throw new IllegalStateException("平台商品详情获取失败: " + result.getErrorMessage());
        }
        return responseParser.parseItemDetailResponse(result.getResponse(), itemId);
    }

    public List<Map<String, Object>> search(String keyword, Long accountId, int limit) {
        return search(keyword, accountId, 1, limit).items();
    }

    public PlatformSearchResult search(String keyword, Long accountId, int pageNumber, int limit) {
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
        int safePageNumber = Math.max(1, pageNumber);
        int safeLimit = Math.max(1, Math.min(limit, 50));
        Map<String, Object> data = buildSearchRequest(keyword, safePageNumber, safeLimit);
        XianyuApiCallUtils.ApiCallResult result = apiCallUtils.callApiWithRetry(
                accountId,
                "mtop.taobao.idlemtopsearch.pc.search",
                data,
                cookieText,
                null,
                Map.of(
                        "spm_cnt", "a21ybx.search.0.0",
                        "spm_pre", "a21ybx.home.searchInput.0"
                ));
        if (!result.isSuccess()) {
            throw new IllegalStateException("平台搜索失败: " + result.getErrorMessage());
        }
        PlatformMarketplaceParser.SearchPage page = responseParser.parseSearchPageResponse(
                result.getResponse(), safeLimit, safePageNumber > 1);
        return new PlatformSearchResult(page.items(), safePageNumber, safeLimit, page.hasMore(), page.total());
    }

    public PlatformSearchResult crawlShop(String shopUrl, Long accountId, int pageNumber, int limit) {
        if (accountId == null) {
            throw new IllegalArgumentException("请选择用于采集的账号");
        }
        String userId = extractShopUserId(shopUrl);
        String cookieText = accountService.getCookieByAccountId(accountId);
        if (cookieText == null || cookieText.isBlank()) {
            throw new IllegalStateException("账号Cookie不可用");
        }
        int safePageNumber = Math.max(1, pageNumber);
        int safeLimit = Math.max(1, Math.min(limit, 50));
        XianyuApiCallUtils.ApiCallResult result = apiCallUtils.callApiWithRetry(
                accountId,
                "mtop.idle.web.xyh.item.list",
                Map.of(
                        "pageNumber", safePageNumber,
                        "pageSize", safeLimit,
                        "needGroupInfo", true,
                        "userId", userId
                ),
                cookieText,
                null,
                Map.of(
                        "spm_cnt", "a21ybx.personal.0.0",
                        "spm_pre", "a21ybx.item.0.0"
                ));
        if (!result.isSuccess()) {
            throw new IllegalStateException("平台店铺商品获取失败: " + result.getErrorMessage());
        }
        PlatformMarketplaceParser.SearchPage page =
                responseParser.parseShopPageResponse(result.getResponse(), safeLimit);
        return new PlatformSearchResult(page.items(), safePageNumber, safeLimit, page.hasMore(), page.total());
    }

    static Map<String, Object> buildSearchRequest(String keyword, int limit) {
        return buildSearchRequest(keyword, 1, limit);
    }

    static Map<String, Object> buildSearchRequest(String keyword, int pageNumber, int limit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pageNumber", Math.max(1, pageNumber));
        data.put("keyword", keyword.trim());
        data.put("fromFilter", false);
        data.put("rowsPerPage", Math.max(1, Math.min(limit, 50)));
        data.put("sortValue", "");
        data.put("sortField", "");
        data.put("customDistance", "");
        data.put("gps", "");
        data.put("propValueStr", Map.of());
        data.put("customGps", "");
        data.put("searchReqFromPage", "pcSearch");
        data.put("extraFilterValue", "{}");
        data.put("userPositionJson", "{}");
        return data;
    }

    static String extractShopUserId(String shopUrl) {
        if (shopUrl == null || shopUrl.isBlank()) {
            throw new IllegalArgumentException("请输入闲鱼店铺链接");
        }
        String value = URLDecoder.decode(shopUrl.trim(), StandardCharsets.UTF_8);
        if (value.matches("\\d{5,}")) {
            return value;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("闲鱼店铺链接格式无效", e);
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equals("goofish.com") || host.endsWith(".goofish.com"))) {
            throw new IllegalArgumentException("仅支持HTTPS闲鱼店铺地址");
        }
        Matcher matcher = SHOP_USER_ID_PATTERN.matcher(value);
        if (!matcher.find()) {
            throw new IllegalArgumentException("店铺链接缺少userId，请从闲鱼网页版店铺主页复制完整地址");
        }
        return matcher.group(1);
    }

    public record PlatformSearchResult(List<Map<String, Object>> items, int pageNumber, int pageSize,
                                       boolean hasMore, long total) { }

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
        if (text(requested.get("divisionId")).isBlank()) {
            requested.putAll(addressCatalog.resolve(requested));
        }
        if (!text(requested.get("divisionId")).isBlank()) {
            Map<String, Object> normalized = normalizeAddress(requested);
            String gps = text(normalized.get("gps"));
            if (gps.isBlank()) {
                return normalized;
            }
            // 使用当前账号 Cookie 将本地区划坐标转换为平台认可的发布地址。
            XianyuApiCallUtils.ApiCallResult locationResult = apiCallUtils.callApiWithRetry(
                    accountId,
                    "mtop.taobao.idle.local.poi.get",
                    "1.0",
                    buildAddressLookupRequest(gps),
                    cookieText,
                    Map.of("eagleeye-userdata", "spm-cnt=a21ybx"),
                    Map.of(
                            "spm_cnt", "a21ybx.publish.0.0",
                            "spm_pre", "a21ybx.item.sidebar.1.38262218ame5nr"
                    )
            );
            if (locationResult.isSuccess()) {
                Map<String, Object> platformAddress =
                        responseParser.extractDefaultAddress(locationResult.getResponse());
                if (!platformAddress.isEmpty()) {
                    return normalizeAddress(platformAddress);
                }
            }
            log.warn("平台定位补全未返回地址，使用本地行政区划: accountId={}, divisionId={}",
                    accountId, normalized.get("divisionId"));
            return normalized;
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

    static Map<String, Object> buildAddressLookupRequest(String gps) {
        String[] coordinates = gps == null ? new String[0] : gps.split(",", 2);
        if (coordinates.length != 2) {
            throw new IllegalArgumentException("发布位置坐标格式无效");
        }
        try {
            return Map.of(
                    "longitude", Double.parseDouble(coordinates[0].trim()),
                    "latitude", Double.parseDouble(coordinates[1].trim())
            );
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("发布位置坐标格式无效", e);
        }
    }

    private Map<String, Object> normalizeAddress(Map<String, Object> address) {
        Map<String, Object> result = new LinkedHashMap<>();
        String gps = firstValue(address, "gps", "addressGps", "location");
        if (gps.isBlank()) {
            String longitude = firstValue(address, "longitude");
            String latitude = firstValue(address, "latitude");
            if (!longitude.isBlank() && !latitude.isBlank()) {
                gps = longitude + "," + latitude;
            }
        }
        result.put("prov", firstValue(address, "prov", "province", "addressProv", "pname"));
        result.put("city", firstValue(address, "city", "addressCity", "cityname"));
        result.put("area", firstValue(address, "area", "district", "addressArea", "adname"));
        result.put("divisionId", numberValue(firstValue(address, "divisionId", "addressDivisionId", "adcode")));
        result.put("gps", gps);
        result.put("poiId", firstValue(address, "poiId", "addressPoiId"));
        result.put("poiName", firstValue(address, "poiName", "poi", "detail", "addressPoiName", "addressText"));
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
